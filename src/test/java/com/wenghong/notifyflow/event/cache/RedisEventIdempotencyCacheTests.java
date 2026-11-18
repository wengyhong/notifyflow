package com.wenghong.notifyflow.event.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import java.util.UUID;

import com.wenghong.notifyflow.event.application.EventSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisEventIdempotencyCacheTests {

    private static final String REQUEST_KEY = "order-128";
    private static final String REDIS_KEY = "notifyflow:event:idempotency:" + REQUEST_KEY;
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final RedisEventIdempotencyCache cache = new RedisEventIdempotencyCache(redis, json);
    private final EventSnapshot snapshot = new EventSnapshot(UUID.randomUUID(), "order.confirmed",
            json.readTree("{\"items\":[{\"quantity\":2}]}"), Instant.parse("2026-11-18T00:00:00.123456Z"));

    @Test
    void putStoresSnapshotJsonForTwentyFourHours() {
        when(redis.opsForValue()).thenReturn(values);

        cache.put(REQUEST_KEY, snapshot);

        verify(values).set(REDIS_KEY, json.writeValueAsString(snapshot), Duration.ofHours(24));
    }

    @Test
    void hitDeserializesOriginalEventDetailsAndPayload() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(REDIS_KEY)).thenReturn(json.writeValueAsString(snapshot));

        assertEquals(snapshot, cache.findByKey(REQUEST_KEY).orElseThrow());
    }

    @Test
    void missingEntryIsCacheMiss() {
        when(redis.opsForValue()).thenReturn(values);

        assertTrue(cache.findByKey(REQUEST_KEY).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "", "{", "[1,2]", "\"text\"", "{\"id\":\"not-a-uuid\"}",
            "{\"createdAt\":\"not-an-instant\",\"payload\":{}}", "{}"})
    void unreadableOrNullDataIsCacheMiss(String stored) {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(REDIS_KEY)).thenReturn(stored);

        assertTrue(cache.findByKey(REQUEST_KEY).isEmpty());
    }

    @Test
    void jsonNullPayloadIsPreservedAsNullNode() {
        EventSnapshot stored = new EventSnapshot(snapshot.id(), snapshot.eventType(),
                json.readTree("null"), snapshot.createdAt());
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(REDIS_KEY)).thenReturn(json.writeValueAsString(stored));

        assertEquals(stored, cache.findByKey(REQUEST_KEY).orElseThrow());
        assertTrue(cache.findByKey(REQUEST_KEY).orElseThrow().payload().isNull());
    }

    @ParameterizedTest
    @MethodSource("redisFailures")
    void readFailureIsCacheMiss(DataAccessException failure) {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(REDIS_KEY)).thenThrow(failure);

        assertTrue(cache.findByKey(REQUEST_KEY).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("redisFailures")
    void writeFailureDoesNotEscape(DataAccessException failure) {
        when(redis.opsForValue()).thenReturn(values);
        doThrow(failure).when(values).set(REDIS_KEY, json.writeValueAsString(snapshot), Duration.ofHours(24));

        assertDoesNotThrow(() -> cache.put(REQUEST_KEY, snapshot));
    }

    @Test
    void serializationFailureDoesNotContactRedis() {
        JsonMapper brokenJson = mock(JsonMapper.class);
        when(brokenJson.writeValueAsString(snapshot)).thenThrow(mock(JacksonException.class));
        var brokenCache = new RedisEventIdempotencyCache(redis, brokenJson);

        assertDoesNotThrow(() -> brokenCache.put(REQUEST_KEY, snapshot));
        verifyNoInteractions(redis);
    }

    static Stream<DataAccessException> redisFailures() {
        return Stream.of(new RedisConnectionFailureException("Test Redis outage"),
                new QueryTimeoutException("Test Redis timeout"));
    }
}
