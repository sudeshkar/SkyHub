package com.skyhub.booking.application;

import com.skyhub.booking.domain.Money;

/**
 * Port: the payment provider (ADR-0015). The application owns this interface; an adapter in the integration
 * package talks to a provider (a mock today), so changing provider means writing one adapter.
 */
public interface PaymentGateway {

    /** A successful charge. {@code id} is the provider's reference, needed to refund it. */
    record Charge(String id, Money amount) {
    }

    /**
     * Takes {@code amount} using the token the provider issued to the browser. The same {@code chargeKey} always
     * returns the same charge and never charges twice, so a retry after a crash is safe. Once a charge has been
     * refunded its key is free again: a retry must make a new charge, never be handed one that was given back.
     *
     * @throws com.skyhub.booking.domain.PaymentDeclinedException when the provider refuses the payment
     * @throws PaymentUnavailableException when the provider cannot be reached or does not answer; nothing was charged
     */
    Charge charge(String paymentToken, Money amount, String chargeKey);

    /**
     * Gives a charge back in full. Safe to repeat.
     *
     * @throws PaymentUnavailableException when the provider cannot be reached
     */
    void refund(String chargeId);
}
