package com.wenghong.notifyflow.delivery.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.wenghong.notifyflow.delivery.configuration.DeliveryRateLimitConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisDeliveryRateLimiterTests {

    private static final UUID ENDPOINT_ID = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
    private static final String KEY = "notifyflow:delivery:rate-limit:" + ENDPOINT_ID;
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisScript<Long> script = new DeliveryRateLimitConfiguration().deliveryRateLimitScript();
    private final RedisDeliveryRateLimiter limiter = new RedisDeliveryRateLimiter(redis, script);

    @Test
    void zeroResultAllowsImmediateSendAndSuppliesEndpointKeyAndScriptArguments() {
        when(redis.execute(script, List.of(KEY), "5", "1000")).thenReturn(0L);

        var decision = limiter.tryAcquire(ENDPOINT_ID);

        assertTrue(decision.allowed());
        assertEquals(Duration.ZERO, decision.retryAfter());
        verify(redis).execute(script, List.of(KEY), "5", "1000");
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 1000, 10000})
    void positiveResultDeniesForExactReturnedMilliseconds(long millis) {
        when(redis.execute(script, List.of(KEY), "5", "1000")).thenReturn(millis);

        var decision = limiter.tryAcquire(ENDPOINT_ID);

        assertFalse(decision.allowed());
        assertEquals(Duration.ofMillis(millis), decision.retryAfter());
    }

    @Test
    void nullResultDefersForOneSecond() {
        when(redis.execute(script, List.of(KEY), "5", "1000")).thenReturn(null);

        assertFallbackDecision();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, -2, Long.MIN_VALUE})
    void negativeResultDefersForOneSecond(long millis) {
        when(redis.execute(script, List.of(KEY), "5", "1000")).thenReturn(millis);

        assertFallbackDecision();
    }

    @ParameterizedTest
    @MethodSource("redisFailures")
    void redisFailureDefersRatherThanGrantingPermission(DataAccessException failure) {
        when(redis.execute(script, List.of(KEY), "5", "1000")).thenThrow(failure);

        assertFallbackDecision();
    }

    static Stream<DataAccessException> redisFailures() {
        return Stream.of(
                new RedisConnectionFailureException("Test connection failure"),
                new QueryTimeoutException("Test timeout"),
                new RedisSystemException("Test script failure", new IllegalStateException("Test cause")));
    }

    @Test
    void missingEndpointIdDoesNotContactRedis() {
        assertThrows(NullPointerException.class, () -> limiter.tryAcquire(null));
        verifyNoInteractions(redis);
    }

    private void assertFallbackDecision() {
        var decision = limiter.tryAcquire(ENDPOINT_ID);
        assertFalse(decision.allowed());
        assertEquals(Duration.ofSeconds(1), decision.retryAfter());
    }
}
