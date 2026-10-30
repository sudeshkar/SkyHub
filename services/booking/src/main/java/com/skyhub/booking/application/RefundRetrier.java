package com.skyhub.booking.application;

import com.skyhub.booking.application.RefundRetryMetrics.Outcome;
import com.skyhub.booking.application.RefundSource.Pending;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Gives back money that is owed and was not returned the first time (ADR-0030): charges for bookings that failed, and
 * refunds owed after a cancellation. It never decides who is owed anything; each {@link RefundSource} does, and the
 * provider's refund is safe to repeat, so a crash between "refunded" and "recorded" only means one more harmless try.
 *
 * <p>Work per sweep is bounded ({@value #BATCH} per source), a failing source does not stop the others, and a refund that fails
 * is retried later with a growing wait and finally reported as given up, so a person knows to act.
 */
@Service
public class RefundRetrier {

    private static final Logger log = LoggerFactory.getLogger(RefundRetrier.class);

    static final int BATCH = 50;

    private final List<RefundSource> sources;
    private final PaymentGateway gateway;
    private final Clock clock;
    private final RefundRetryMetrics metrics;

    public RefundRetrier(List<RefundSource> sources, PaymentGateway gateway, Clock clock, RefundRetryMetrics metrics) {
        this.sources = sources;
        this.gateway = gateway;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** @return how many refunds were paid */
    public int sweep() {
        return sweep(clock.instant());
    }

    /** As {@link #sweep()}, as if it were {@code now}: lets tests ask for the refunds due later. */
    public int sweep(Instant now) {
        int paid = 0;
        for (RefundSource source : sources) {
            List<Pending> due;
            try {
                due = source.claimDue(now, BATCH);
            } catch (RuntimeException e) {
                log.atError().addKeyValue("refund.source", source.name()).setCause(e).log("Could not look for refunds that are due");
                continue;
            }
            for (Pending pending : due) {
                paid += retry(source, pending, now) ? 1 : 0;
            }
        }
        return paid;
    }

    private boolean retry(RefundSource source, Pending pending, Instant now) {
        try {
            gateway.refund(pending.chargeId());
        } catch (RuntimeException failed) {
            boolean last = pending.attempt() >= RefundBackoff.MAX_ATTEMPTS;
            metrics.record(source.name(), last ? Outcome.GAVE_UP : Outcome.FAILED);
            if (last) {
                // Money is owed, we have tried for more than a day: a person refunds this by hand (runbook).
                log.atError().addKeyValue("refund.source", source.name()).addKeyValue("payment.charge", pending.chargeId())
                        .addKeyValue("refund.attempts", pending.attempt()).setCause(failed)
                        .log("Gave up refunding a charge; it needs a person");
            } else {
                log.atWarn().addKeyValue("refund.source", source.name()).addKeyValue("payment.charge", pending.chargeId())
                        .addKeyValue("refund.attempts", pending.attempt()).setCause(failed).log("Refund retry failed; will try again later");
            }
            return false;
        }
        try {
            source.refunded(pending, now);
        } catch (RuntimeException e) {
            // The money went back; only the note failed. The next try asks the provider again, which is harmless.
            log.atError().addKeyValue("refund.source", source.name()).addKeyValue("payment.charge", pending.chargeId())
                    .setCause(e).log("Refunded, but could not record it");
        }
        metrics.record(source.name(), Outcome.REFUNDED);
        log.atInfo().addKeyValue("refund.source", source.name()).addKeyValue("payment.charge", pending.chargeId())
                .addKeyValue("refund.attempts", pending.attempt()).log("Refund retry succeeded");
        return true;
    }
}
