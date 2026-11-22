package com.wenghong.notifyflow.delivery.observability;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class DeliveryMetrics {

    private final Map<DeliveryAttemptOutcome, Counter> attempts = new EnumMap<>(DeliveryAttemptOutcome.class);
    private final Timer attemptDuration;

    public DeliveryMetrics(MeterRegistry registry) {
        for (DeliveryAttemptOutcome outcome : DeliveryAttemptOutcome.values()) {
            Counter counter = Counter.builder("notifyflow.webhook.attempts")
                    .description("Webhook sending attempts by outcome")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .register(registry);

            attempts.put(outcome, counter);
        }

        this.attemptDuration = Timer.builder("notifyflow.webhook.duration")
                .description("Time spent sending webhook requests")
                .register(registry);

    }

    public void recordAttempt(
            DeliveryAttemptOutcome outcome,
            Duration elapsed) {

        Objects.requireNonNull(outcome);
        Objects.requireNonNull(elapsed);

        if (elapsed.isNegative()) {
            throw new IllegalArgumentException(
                    "Elapsed time must not be negative.");
        }

        attemptDuration.record(elapsed);
        attempts.get(outcome).increment();
    }
}
