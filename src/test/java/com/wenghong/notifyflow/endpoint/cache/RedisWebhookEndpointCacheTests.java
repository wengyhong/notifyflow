package com.wenghong.notifyflow.endpoint.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import com.wenghong.notifyflow.endpoint.application.WebhookEndpointSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisWebhookEndpointCacheTests {

    private static final UUID ID = UUID.fromString("1990fcb2-3db0-4664-b949-3892111ba544");
    private static final String KEY = "notifyflow:endpoint:" + ID;
    private static final Instant CREATED = Instant.parse("2026-11-18T00:00:00.123456Z");
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final RedisWebhookEndpointCache cache = new RedisWebhookEndpointCache(redis, json);

    @Test
    void putStoresPublicSnapshotAsJsonWithSixtySecondExpiry() {
        when(redis.opsForValue()).thenReturn(values);
        WebhookEndpointSnapshot snapshot = snapshot(true);

        cache.put(snapshot);

        var value = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(KEY), value.capture(), eq(Duration.ofSeconds(60)));
        assertEquals(snapshot, json.readValue(value.getValue(), WebhookEndpointSnapshot.class));
        var tree = json.readTree(value.getValue());
        assertEquals(6, tree.size());
        assertFalse(tree.has("signingSecret"));
        assertFalse(tree.has("previousSigningSecret"));
        assertFalse(tree.has("previousSigningSecretExpiresAt"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readRestoresAllSnapshotFields(boolean enabled) {
        WebhookEndpointSnapshot snapshot = snapshot(enabled);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(KEY)).thenReturn(json.writeValueAsString(snapshot));

        assertEquals(snapshot, cache.findById(ID).orElseThrow());
        verify(values).get(KEY);
    }

    @Test
    void absentKeyIsCacheMiss() {
        when(redis.opsForValue()).thenReturn(values);

        assertTrue(cache.findById(ID).isEmpty());
        verify(values).get(KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "", "{", "[1,2]", "\"text\"", "{\"id\":\"not-a-uuid\"}"})
    void unreadableOrNullJsonIsCacheMiss(String stored) {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(KEY)).thenReturn(stored);

        assertTrue(cache.findById(ID).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("redisFailures")
    void readFailureIsCacheMiss(DataAccessException failure) {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(KEY)).thenThrow(failure);

        assertTrue(cache.findById(ID).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("redisFailures")
    void writeFailureDoesNotEscape(DataAccessException failure) {
        when(redis.opsForValue()).thenReturn(values);
        WebhookEndpointSnapshot snapshot = snapshot(true);
        doThrow(failure).when(values).set(KEY, json.writeValueAsString(snapshot), Duration.ofSeconds(60));

        assertDoesNotThrow(() -> cache.put(snapshot));
    }

    @Test
    void serializationFailureDoesNotContactRedisOrEscape() {
        JsonMapper brokenJson = mock(JsonMapper.class);
        WebhookEndpointSnapshot snapshot = snapshot(true);
        when(brokenJson.writeValueAsString(snapshot)).thenThrow(mock(JacksonException.class));
        var brokenCache = new RedisWebhookEndpointCache(redis, brokenJson);

        assertDoesNotThrow(() -> brokenCache.put(snapshot));
        verifyNoInteractions(redis);
    }

    @Test
    void evictDeletesOnlyTheRequestedKey() {
        cache.evict(ID);

        verify(redis).delete(KEY);
    }

    @ParameterizedTest
    @MethodSource("redisFailures")
    void evictionFailureDoesNotEscape(DataAccessException failure) {
        when(redis.delete(KEY)).thenThrow(failure);

        assertDoesNotThrow(() -> cache.evict(ID));
    }

    static Stream<DataAccessException> redisFailures() {
        return Stream.of(new RedisConnectionFailureException("Test connection failure"),
                new QueryTimeoutException("Test command timeout"));
    }

    private WebhookEndpointSnapshot snapshot(boolean enabled) {
        return new WebhookEndpointSnapshot(ID, "Warehouse \"東京\"", "https://example.com/webhook",
                enabled, CREATED, CREATED.plusSeconds(10));
    }
}
