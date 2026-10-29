package com.skyhub.booking.application;

import com.skyhub.booking.application.rules.BookingRule;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Applies every {@link BookingRule} in order. The rules are injected, so this class never changes
 * when a rule is added. The API contract already checks formats; rules cover what a schema cannot.
 */
@Component
public class CreatePnrValidator {

    private final List<BookingRule> rules;

    public CreatePnrValidator(List<BookingRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public void validate(CreatePnrCommand command) {
        rules.forEach(rule -> rule.check(command));
    }
}
