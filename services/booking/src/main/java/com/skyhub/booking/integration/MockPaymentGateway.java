package com.skyhub.booking.integration;

import com.skyhub.booking.application.PaymentGateway;
import com.skyhub.booking.application.PaymentUnavailableException;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.PaymentDeclinedException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Stands in for a real payment provider so the whole payment flow can be built and tested without one.
 * Behaves like one where it matters: the same charge key never charges twice, a decline is an answer, and an
 * outage is a different kind of failure. Three test tokens: {@code tok_visa} (approved), {@code tok_declined},
 * {@code tok_provider_down}. Anything else is declined. State is in memory, so it is per instance and lost on restart.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    private final Map<String, Charge> chargesByKey = new ConcurrentHashMap<>();
    private final Map<String, Charge> chargesById = new ConcurrentHashMap<>();
    private final Set<String> refunded = ConcurrentHashMap.newKeySet();

    @Override
    public Charge charge(String paymentToken, Money amount, String chargeKey) {
        Charge earlier = chargesByKey.get(chargeKey);
        if (earlier != null) {
            return earlier;
        }
        switch (paymentToken) {
            case "tok_visa" -> {
                // computeIfAbsent: two requests with one key, at the same moment, still make one charge.
                return chargesByKey.computeIfAbsent(chargeKey, key -> {
                    Charge charge = new Charge("ch_" + UUID.randomUUID().toString().replace("-", ""), amount);
                    chargesById.put(charge.id(), charge);
                    return charge;
                });
            }
            case "tok_provider_down" -> throw new PaymentUnavailableException("The payment provider did not answer", null);
            case "tok_declined" -> throw new PaymentDeclinedException("Your card was declined");
            default -> throw new PaymentDeclinedException("This payment method was not accepted");
        }
    }

    @Override
    public void refund(String chargeId) {
        if (!chargesById.containsKey(chargeId)) {
            throw new IllegalArgumentException("No such charge: " + chargeId);
        }
        refunded.add(chargeId);
        // A refunded charge must not be handed back for its key: a retried booking would then go through unpaid.
        chargesByKey.values().removeIf(charge -> charge.id().equals(chargeId));
    }

    /** For tests: how many distinct charges were made. */
    public int chargeCount() {
        return chargesById.size();
    }

    /** For tests: how many distinct charges were refunded. */
    public int refundCount() {
        return refunded.size();
    }

    /** For tests. */
    public boolean isRefunded(String chargeId) {
        return refunded.contains(chargeId);
    }
}
