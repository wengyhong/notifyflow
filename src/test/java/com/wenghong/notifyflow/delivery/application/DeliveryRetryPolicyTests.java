package com.wenghong.notifyflow.delivery.application;

import java.time.Duration;
import java.util.random.RandomGenerator;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeliveryRetryPolicyTests {

    private final RandomGenerator random = mock(RandomGenerator.class);
    private final DeliveryRetryPolicy policy = new DeliveryRetryPolicy(random);

    @ParameterizedTest
    @CsvSource({
            "1, 5000, 1000",
            "2, 10000, 2000",
            "3, 20000, 4000",
            "4, 40000, 8000"
    })
    void retryDelayIncludesMaximumJitter(
            int attemptNumber, long baseMillis, long maxJitterMillis) {
        when(random.nextLong(maxJitterMillis + 1)).thenReturn(maxJitterMillis);

        assertEquals(Duration.ofMillis(baseMillis + maxJitterMillis), policy.retryDelay(attemptNumber));

        verify(random).nextLong(maxJitterMillis + 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504, 599})
    void timeoutRateLimitAndServerErrorsAreRetryable(int responseStatus) {
        assertTrue(policy.isRetryableStatus(responseStatus));
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 200, 204, 299, 300, 400, 401, 403, 404, 407, 409, 428, 430, 499})
    void otherStatusesAreNotRetryable(int responseStatus) {
        assertFalse(policy.isRetryableStatus(responseStatus));
    }

    @ParameterizedTest
    @CsvSource({
            "1, true",
            "2, true",
            "3, true",
            "4, true",
            "5, false",
            "6, false",
            "2147483647, false"
    })
    void retryLimitAllowsFiveTotalAttempts(int completedAttemptNumber, boolean expected) {
        assertEquals(expected, policy.canRetry(completedAttemptNumber));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void retryEligibilityRejectsNonPositiveAttemptNumbers(int attemptNumber) {
        assertThrows(IllegalArgumentException.class, () -> policy.canRetry(attemptNumber));
    }

    @ParameterizedTest
    @CsvSource({
            "1, 5",
            "2, 10",
            "3, 20",
            "4, 40"
    })
    void retryDelayDoublesAfterEachFailedAttempt(int attemptNumber, long expectedSeconds) {
        long bound = expectedSeconds * 1000 / 5 + 1;
        when(random.nextLong(bound)).thenReturn(0L);

        assertEquals(Duration.ofSeconds(expectedSeconds), policy.retryDelay(attemptNumber));
        verify(random).nextLong(bound);
    }

    @ParameterizedTest
    @CsvSource({"1, 5000, 1001, 375", "2, 10000, 2001, 1250",
            "3, 20000, 4001, 2000", "4, 40000, 8001, 7000"})
    void retryDelayPreservesIntermediateJitterInMilliseconds(
            int attemptNumber, long baseMillis, long bound, long jitterMillis) {
        when(random.nextLong(bound)).thenReturn(jitterMillis);

        assertEquals(Duration.ofMillis(baseMillis + jitterMillis), policy.retryDelay(attemptNumber));
        verify(random).nextLong(bound);
    }

    @Test
    void eachDelayCalculationDrawsFreshJitter() {
        when(random.nextLong(1001L)).thenReturn(0L, 500L);

        assertEquals(Duration.ofSeconds(5), policy.retryDelay(1));
        assertEquals(Duration.ofMillis(5500), policy.retryDelay(1));
        verify(random, times(2)).nextLong(1001L);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void retryDelayRejectsNonPositiveAttemptNumbers(int attemptNumber) {
        assertThrows(IllegalArgumentException.class, () -> policy.retryDelay(attemptNumber));
        verifyNoInteractions(random);
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 6, Integer.MAX_VALUE})
    void retryDelayRejectsExhaustedAttempts(int attemptNumber) {
        assertThrows(IllegalArgumentException.class, () -> policy.retryDelay(attemptNumber));
        verifyNoInteractions(random);
    }
}
