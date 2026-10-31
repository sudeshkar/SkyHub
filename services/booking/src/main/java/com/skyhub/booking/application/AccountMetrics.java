package com.skyhub.booking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * {@code skyhub_account_logins_total{outcome}} (success, failed, locked), {@code skyhub_account_password_links_total{outcome}}
 * (sent, throttled) and {@code skyhub_account_passwords_total{outcome}} (set, invalid_link).
 */
@Component
public class AccountMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public AccountMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void login(String outcome) {
        count("skyhub.account.logins", "Traveller sign-in attempts, by outcome", outcome);
    }

    public void link(String outcome) {
        count("skyhub.account.password.links", "Password links requested, by outcome", outcome);
    }

    public void password(String outcome) {
        count("skyhub.account.passwords", "Passwords chosen with an emailed link, by outcome", outcome);
    }

    /** {@code revoked}: an account token refused because the account's credentials changed after it was issued (ADR-0041). */
    public void session(String outcome) {
        count("skyhub.account.sessions", "Account sessions refused, by outcome", outcome);
    }

    /** requested, throttled, taken (the new address has an account), password_refused, confirmed, invalid_link (ADR-0039). */
    public void emailChange(String outcome) {
        count("skyhub.account.email.changes", "Changes of an account's address, by outcome", outcome);
    }

    private void count(String name, String description, String outcome) {
        counters.computeIfAbsent(name + "/" + outcome, key -> Counter.builder(name).description(description)
                .tag("outcome", outcome).register(registry)).increment();
    }
}
