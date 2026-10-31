package com.skyhub.notification.messaging;

import com.skyhub.notification.application.SkippedLetters;
import com.skyhub.notification.application.SkippedLetters.Skip;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.stereotype.Component;

/**
 * An operator's decision to leave one parked message out of the replay (ADR-0037). Only a message that is really waiting can be
 * skipped, and a reason is required, so the list holds nothing a typo put there and every entry says why.
 */
@Component
class DeadLetterSkips {

    static final int MAX_REASON = 200;

    private static final Logger log = LoggerFactory.getLogger(DeadLetterSkips.class);

    private final SkippedLetters store;
    private final ParkedLookup parked;
    private final Clock clock;

    DeadLetterSkips(SkippedLetters store, ParkedLookup parked, Clock clock) {
        this.store = store;
        this.parked = parked;
        this.clock = clock;
    }

    Skip skip(int partition, long offset, String reason) {
        if (partition < 0 || offset < 0) {
            throw invalid("The partition and offset must not be negative");
        }
        if (reason == null || reason.isBlank() || reason.strip().length() > MAX_REASON) {
            throw invalid("Give a reason of 1 to " + MAX_REASON + " characters");
        }
        if (!parked.isWaiting(partition, offset)) {
            throw invalid("That message is not waiting on the dead-letter topic (partition " + partition + ", offset " + offset + ")");
        }
        String cleaned = ParkedMessage.hideAddresses(reason.strip());
        store.skip(partition, offset, cleaned, clock.instant());
        log.atInfo().addKeyValue("dlt.partition", partition).addKeyValue("dlt.offset", offset).addKeyValue("dlt.skip.reason", cleaned)
                .log("A parked message will be left out of the replay");
        // The entry that is stored, which is the first one if the message was already listed.
        return store.all().stream().filter(s -> s.partition() == partition && s.offset() == offset).findFirst()
                .orElse(new Skip(partition, offset, cleaned, clock.instant()));
    }

    /** @return false if the message was not listed */
    boolean unskip(int partition, long offset) {
        return store.unskip(partition, offset);
    }

    List<Skip> listed() {
        return store.all();
    }

    private static InvalidEndpointRequestException invalid(String message) {
        return new InvalidEndpointRequestException(message, message);
    }
}
