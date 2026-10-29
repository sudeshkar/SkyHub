package com.skyhub.booking.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/** An amount in one currency, always to the cent. BigDecimal, never double: 0.1 + 0.2 must equal 0.3. */
public record Money(BigDecimal amount, String currency) {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public Money {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("An amount cannot be negative");
        }
        if (!CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("A currency is three upper-case letters, e.g. USD");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money times(int quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot combine currencies " + currency + " and " + other.currency);
        }
    }
}
