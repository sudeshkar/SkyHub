package com.skyhub.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a booking was paid. Holds the provider's charge reference and amounts, never card details (we never
 * receive any). Written in the same transaction as the booking it pays for.
 */
@Entity
@Table(name = "payment")
public class Payment {

    public enum Status {
        CAPTURED, REFUNDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 6, unique = true)
    private String locator;

    @Column(nullable = false, length = 64)
    private String chargeId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false)
    private Instant createdAt;

    protected Payment() {
    }

    public static Payment captured(String locator, String chargeId, Money amount, Instant createdAt) {
        Payment payment = new Payment();
        payment.locator = locator;
        payment.chargeId = chargeId;
        payment.amount = amount.amount();
        payment.currency = amount.currency();
        payment.status = Status.CAPTURED;
        payment.createdAt = createdAt;
        return payment;
    }

    public String getLocator() {
        return locator;
    }

    public String getChargeId() {
        return chargeId;
    }

    public Money getAmount() {
        return Money.of(amount, currency);
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
