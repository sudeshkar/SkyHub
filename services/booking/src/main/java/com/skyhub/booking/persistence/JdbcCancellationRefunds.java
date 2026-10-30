package com.skyhub.booking.persistence;

import com.skyhub.booking.application.BookingEvents;
import com.skyhub.booking.application.PaymentMetrics;
import com.skyhub.booking.application.RefundBackoff;
import com.skyhub.booking.application.RefundSource;
import com.skyhub.booking.domain.Payment;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Refunds owed to travellers who cancelled (ADR-0016) and whose first refund did not go through. The traveller can still
 * press Cancel again, which also pays them; both paths are safe together because the provider's refund can be repeated and
 * the payment moves to REFUNDED only once.
 */
@Component
class JdbcCancellationRefunds implements RefundSource {

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final PaymentRepository payments;
    private final PaymentMetrics paymentMetrics;
    private final PnrRepository pnrs;
    private final BookingEvents events;

    JdbcCancellationRefunds(JdbcTemplate jdbc, TransactionOperations transactions, PaymentRepository payments,
                            PaymentMetrics paymentMetrics, PnrRepository pnrs, BookingEvents events) {
        this.pnrs = pnrs;
        this.events = events;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.payments = payments;
        this.paymentMetrics = paymentMetrics;
    }

    @Override
    public String name() {
        return "cancellation";
    }

    @Override
    public List<Pending> claimDue(Instant now, int limit) {
        return transactions.execute(status -> {
            List<Pending> claimed = jdbc.query("SELECT id, charge_id, refund_attempts FROM payment "
                            + "WHERE status = 'REFUND_DUE' AND refund_attempts < ? "
                            + "AND (refund_next_attempt_at IS NULL OR refund_next_attempt_at <= ?) "
                            + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, row) -> new Pending(rs.getLong(1), rs.getString(2), rs.getInt(3) + 1),
                    RefundBackoff.MAX_ATTEMPTS, Timestamp.from(now), limit);
            for (Pending p : claimed) {
                jdbc.update("UPDATE payment SET refund_attempts = ?, refund_next_attempt_at = ? WHERE id = ?",
                        p.attempt(), Timestamp.from(now.plus(RefundBackoff.delayAfter(p.attempt()))), p.id());
            }
            return claimed;
        });
    }

    @Override
    public void refunded(Pending pending, Instant now) {
        transactions.executeWithoutResult(status -> payments.findById(pending.id())
                .filter(payment -> payment.getStatus() == Payment.Status.REFUND_DUE)   // the traveller may have been paid meanwhile
                .ifPresent(payment -> {
                    payment.refunded();
                    payments.save(payment);
                    paymentMetrics.record(PaymentMetrics.Outcome.CANCEL_REFUNDED);
                    // Written with the status change, so the traveller is told exactly once (ADR-0031).
                    pnrs.findByLocator(payment.getLocator()).ifPresent(pnr -> events.refundCompleted(pnr, payment.getAmount()));
                }));
    }
}
