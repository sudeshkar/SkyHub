package com.skyhub.notification.messaging;

import com.skyhub.notification.application.SkippedLetters.Skip;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * {@code GET /actuator/deadletterskips} shows the parked messages the replay will leave out; {@code POST} with
 * {@code {"partition", "offset", "reason", "operator"}} adds one; {@code DELETE ?partition=&offset=&operator=} takes one off again
 * (ADR-0037). Every change says who made it (ADR-0052). On the
 * management port, like the replay tool (ADR-0032): that port is never exposed publicly.
 */
@Component
@Endpoint(id = "deadletterskips")
class DeadLetterSkipsEndpoint {

    /** {@code removed} is false when nothing was listed at that place. */
    record Unskipped(boolean removed) {
    }

    private final DeadLetterSkips skips;

    DeadLetterSkipsEndpoint(DeadLetterSkips skips) {
        this.skips = skips;
    }

    @ReadOperation
    List<Skip> listed() {
        return skips.listed();
    }

    @WriteOperation
    Skip skip(int partition, long offset, @Nullable String reason, @Nullable String operator) {
        return skips.skip(partition, offset, reason, Operator.named(operator));
    }

    @DeleteOperation
    Unskipped unskip(int partition, long offset, @Nullable String operator) {
        return new Unskipped(skips.unskip(partition, offset, Operator.named(operator)));
    }
}
