package com.skyhub.notification.messaging;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * {@code GET /actuator/deadletters?limit=} shows what is parked; {@code POST /actuator/deadletters} with {@code {"limit": n}}
 * puts it back (ADR-0032); the person says who they are with {@code "operator"} (ADR-0052). It is an actuator endpoint on purpose: that port is never exposed publicly (ADR-0010), so the
 * tool needs no login of its own and no public API.
 */
@Component
@Endpoint(id = "deadletters")
class DeadLetterEndpoint {

    private final DeadLetterReplayer replayer;

    DeadLetterEndpoint(DeadLetterReplayer replayer) {
        this.replayer = replayer;
    }

    @ReadOperation
    DeadLetterReplayer.Parked parked(@Nullable Integer limit) {
        return replayer.peek(limit == null ? 0 : limit);
    }

    @WriteOperation
    DeadLetterReplayer.Replay replay(@Nullable Integer limit, @Nullable String operator) {
        return replayer.replay(limit == null ? 0 : limit, Operator.named(operator));
    }
}
