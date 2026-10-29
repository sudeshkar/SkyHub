package com.skyhub.booking.application;

import com.skyhub.booking.domain.DisruptionType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@code skyhub_disruptions_total{type}}: how many delays and cancellations operations have recorded. */
@Component
public class DisruptionMetrics {

    private final Map<DisruptionType, Counter> counters = new EnumMap<>(DisruptionType.class);

    public DisruptionMetrics(MeterRegistry registry) {
        for (DisruptionType type : DisruptionType.values()) {
            counters.put(type, Counter.builder("skyhub.disruptions")
                    .description("Flight disruptions recorded, by type")
                    .tag("type", type.name().toLowerCase(Locale.ROOT))
                    .register(registry));
        }
    }

    public void record(DisruptionType type) {
        counters.get(type).increment();
    }
}
