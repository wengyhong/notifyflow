package com.wenghong.notifyflow.delivery.application;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryRateLimitDecisionTests {

    @Test
    void acceptsImmediatePermissionWithZeroDelay() {
        DeliveryRateLimitDecision decision = new DeliveryRateLimitDecision(true, Duration.ZERO);

        assertTrue(decision.allowed());
        assertEquals(Duration.ZERO, decision.retryAfter());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 1_000_000, 5_000_000_000L})
    void acceptsDeniedDecisionWithPositiveDelay(long nanos) {
        Duration delay = Duration.ofNanos(nanos);

        DeliveryRateLimitDecision decision = new DeliveryRateLimitDecision(false, delay);

        assertFalse(decision.allowed());
        assertEquals(delay, decision.retryAfter());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1_000_000_000, -1_000_000, -1, 1, 1_000_000, 1_000_000_000})
    void rejectsAllowedDecisionWithAnyNonzeroDelay(long nanos) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DeliveryRateLimitDecision(true, Duration.ofNanos(nanos)));

        assertEquals("An allowed decision must have zero retry delay", error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1_000_000, -1, 0})
    void rejectsDeniedDecisionWithZeroOrNegativeDelay(long nanos) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DeliveryRateLimitDecision(false, Duration.ofNanos(nanos)));

        assertEquals("A denied decision must have a positive retry delay", error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsNullDelayForEitherDecision(boolean allowed) {
        assertThrows(NullPointerException.class, () -> new DeliveryRateLimitDecision(allowed, null));
    }
}
