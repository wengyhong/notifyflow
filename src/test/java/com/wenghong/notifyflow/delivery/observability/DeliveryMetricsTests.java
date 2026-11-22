package com.wenghong.notifyflow.delivery.observability;

import java.util.Locale;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryMetricsTests {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DeliveryMetrics metrics = new DeliveryMetrics(registry);

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void allOutcomesAreAvailableWithZeroCountsBeforeAnyAttempts() {
        assertEquals(4, registry.get("notifyflow.webhook.attempts").counters().size());
        assertEquals(0, registry.get("notifyflow.webhook.duration").timer().count());
        for (DeliveryAttemptOutcome outcome : DeliveryAttemptOutcome.values()) {
            assertEquals(0.0, count(outcome));
        }
    }

    @ParameterizedTest
    @EnumSource(DeliveryAttemptOutcome.class)
    void repeatedAttemptsIncreaseOnlyTheSelectedOutcome(DeliveryAttemptOutcome selected) {
        metrics.recordAttempt(selected, Duration.ofMillis(100));
        metrics.recordAttempt(selected, Duration.ofMillis(250));

        for (DeliveryAttemptOutcome outcome : DeliveryAttemptOutcome.values()) {
            assertEquals(outcome == selected ? 2.0 : 0.0, count(outcome));
        }
        assertEquals(4, registry.get("notifyflow.webhook.attempts").counters().size());
        var timer = registry.get("notifyflow.webhook.duration").timer();
        assertEquals(2, timer.count());
        assertEquals(350.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.000001);
        assertEquals(250.0, timer.max(TimeUnit.MILLISECONDS), 0.000001);
    }

    @Test
    void nullOutcomeIsRejectedWithoutIncrementingAnyCounter() {
        assertThrows(NullPointerException.class, () -> metrics.recordAttempt(null, Duration.ofMillis(100)));
        assertNoAttempts();
    }

    @Test
    void nullDurationDoesNotChangeEitherMetric() {
        assertThrows(NullPointerException.class,
                () -> metrics.recordAttempt(DeliveryAttemptOutcome.SUCCESS, null));
        assertNoAttempts();
    }

    @Test
    void negativeDurationDoesNotChangeEitherMetric() {
        assertThrows(IllegalArgumentException.class,
                () -> metrics.recordAttempt(DeliveryAttemptOutcome.SUCCESS, Duration.ofNanos(-1)));
        assertNoAttempts();
    }

    @Test
    void zeroDurationStillCountsAsAnAttempt() {
        metrics.recordAttempt(DeliveryAttemptOutcome.SUCCESS, Duration.ZERO);
        assertEquals(1.0, count(DeliveryAttemptOutcome.SUCCESS));
        var timer = registry.get("notifyflow.webhook.duration").timer();
        assertEquals(1, timer.count());
        assertEquals(0.0, timer.totalTime(TimeUnit.NANOSECONDS));
    }

    private void assertNoAttempts() {
        assertEquals(0, registry.get("notifyflow.webhook.duration").timer().count());
        for (DeliveryAttemptOutcome outcome : DeliveryAttemptOutcome.values()) {
            assertEquals(0.0, count(outcome));
        }
    }

    private double count(DeliveryAttemptOutcome outcome) {
        return registry.get("notifyflow.webhook.attempts")
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .counter().count();
    }
}
