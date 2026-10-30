package com.skyhub.booking.persistence;

import com.skyhub.booking.application.PaymentMetrics;
import com.skyhub.booking.application.RefundBackoff;
import com.skyhub.booking.application.RefundSource;
import com.skyhub.booking.application.UnrefundedCharges;
import com.skyhub.booking.domain.Money;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Charges taken for bookings that failed, awaiting their refund (ADR-0030). Both the note and the retry are plain SQL:
 * {@code FOR UPDATE SKIP LOCKED} lets several instances share the work without waiting for each other, and a partial index
 * holds only the unfinished rows, so the cost of looking does not grow with the history.
 */
@Component
class JdbcUnrefundedCharges implements UnrefundedCharges, RefundSource {

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final PaymentMetrics paymentMetrics;

    JdbcUnrefundedCharges(JdbcTemplate jdbc, TransactionOperations transactions, PaymentMetrics paymentMetrics) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.paymentMetrics = paymentMetrics;
    }

    @Override
    public void record(String chargeId, Money amount, String reason, Instant at) {
        // The first retry comes after the normal first wait: the refund has only just failed.
        jdbc.update("INSERT INTO unrefunded_charge (charge_id, amount, currency, reason, created_at, next_attempt_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (charge_id) DO NOTHING",
                chargeId, amount.amount(), amount.currency(), reason.length() > 100 ? reason.substring(0, 100) : reason,
                Timestamp.from(at), Timestamp.from(at.plus(RefundBackoff.delayAfter(1))));
    }

    @Override
    public String name() {
        return "failed-booking";
    }

    @Override
    public List<Pending> claimDue(Instant now, int limit) {
        return transactions.execute(status -> {
            List<Pending> claimed = jdbc.query("SELECT id, charge_id, attempts FROM unrefunded_charge "
                            + "WHERE refunded_at IS NULL AND attempts < ? AND next_attempt_at <= ? "
                            + "ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, row) -> new Pending(rs.getLong(1), rs.getString(2), rs.getInt(3) + 1),
                    RefundBackoff.MAX_ATTEMPTS, Timestamp.from(now), limit);
            for (Pending p : claimed) {
                jdbc.update("UPDATE unrefunded_charge SET attempts = ?, next_attempt_at = ? WHERE id = ?",
                        p.attempt(), Timestamp.from(now.plus(RefundBackoff.delayAfter(p.attempt()))), p.id());
            }
            return claimed;
        });
    }

    @Override
    public void refunded(Pending pending, Instant now) {
        if (jdbc.update("UPDATE unrefunded_charge SET refunded_at = ? WHERE id = ? AND refunded_at IS NULL",
                Timestamp.from(now), pending.id()) == 1) {
            paymentMetrics.record(PaymentMetrics.Outcome.REFUNDED);
        }
    }
}
