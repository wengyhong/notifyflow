package com.wenghong.notifyflow.endpoint.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.cache.RedisWebhookEndpointCache;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WebhookEndpointQueryServiceTests {

    private static final UUID ID = UUID.fromString("1990fcb2-3db0-4664-b949-3892111ba544");
    private static final Instant CREATED = Instant.parse("2026-11-18T00:00:00Z");
    private final WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
    private final WebhookEndpointCache cache = mock(WebhookEndpointCache.class);
    private final WebhookEndpointQueryService service = new WebhookEndpointQueryService(repository, cache);

    @Test
    void cacheHitReturnsSnapshotWithoutLoadingPostgresOrRewritingCache() {
        WebhookEndpointSnapshot snapshot = snapshot(false);
        when(cache.findById(ID)).thenReturn(Optional.of(snapshot));

        assertSame(snapshot, service.findById(ID).orElseThrow());

        verify(cache).findById(ID);
        verifyNoMoreInteractions(cache);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cacheMissLoadsAllPublicFieldsAndCachesSnapshot(boolean enabled) {
        when(cache.findById(ID)).thenReturn(Optional.empty());
        WebhookEndpoint endpoint = endpoint(enabled);
        when(repository.findById(ID)).thenReturn(Optional.of(endpoint));

        assertEquals(snapshot(enabled), service.findById(ID).orElseThrow());

        verify(repository).findById(ID);
        verify(cache).findById(ID);
        verify(cache).put(snapshot(enabled));
        verifyNoMoreInteractions(cache, repository);
    }

    @Test
    void missingEndpointReturnsEmptyWithoutCachingAbsence() {
        when(cache.findById(ID)).thenReturn(Optional.empty());
        when(repository.findById(ID)).thenReturn(Optional.empty());

        assertTrue(service.findById(ID).isEmpty());

        verify(repository).findById(ID);
        verify(cache).findById(ID);
        verifyNoMoreInteractions(cache, repository);
    }

    @Test
    void databaseFailurePropagatesWithoutWritingCache() {
        when(cache.findById(ID)).thenReturn(Optional.empty());
        var failure = new DataAccessResourceFailureException("Test PostgreSQL failure");
        when(repository.findById(ID)).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.findById(ID)));

        verify(cache, never()).put(any(WebhookEndpointSnapshot.class));
    }

    @Test
    void redisReadAndWriteFailuresStillReturnPostgresSnapshot() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("Test Redis outage"));
        var actualCache = new RedisWebhookEndpointCache(redis, JsonMapper.builder().build());
        var query = new WebhookEndpointQueryService(repository, actualCache);
        WebhookEndpoint endpoint = endpoint(true);
        when(repository.findById(ID)).thenReturn(Optional.of(endpoint));

        assertEquals(snapshot(true), query.findById(ID).orElseThrow());

        verify(repository).findById(ID);
    }

    @Test
    void redisWriteFailureAfterCacheMissDoesNotDiscardDatabaseResult() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var json = JsonMapper.builder().build();
        var actualCache = new RedisWebhookEndpointCache(redis, json);
        var query = new WebhookEndpointQueryService(repository, actualCache);
        WebhookEndpoint endpoint = endpoint(true);
        when(repository.findById(ID)).thenReturn(Optional.of(endpoint));
        String key = "notifyflow:endpoint:" + ID;
        doThrow(new RedisConnectionFailureException("Test write failure"))
                .when(values).set(key, json.writeValueAsString(snapshot(true)), Duration.ofSeconds(60));

        assertEquals(snapshot(true), query.findById(ID).orElseThrow());

        verify(values).get(key);
        verify(values).set(key, json.writeValueAsString(snapshot(true)), Duration.ofSeconds(60));
    }

    private WebhookEndpoint endpoint(boolean enabled) {
        WebhookEndpoint endpoint = mock(WebhookEndpoint.class);
        when(endpoint.getId()).thenReturn(ID);
        when(endpoint.getName()).thenReturn("Warehouse");
        when(endpoint.getUrl()).thenReturn("https://example.com/webhook");
        when(endpoint.isEnabled()).thenReturn(enabled);
        when(endpoint.getCreatedAt()).thenReturn(CREATED);
        when(endpoint.getUpdatedAt()).thenReturn(CREATED.plusSeconds(60));
        return endpoint;
    }

    private WebhookEndpointSnapshot snapshot(boolean enabled) {
        return new WebhookEndpointSnapshot(ID, "Warehouse", "https://example.com/webhook",
                enabled, CREATED, CREATED.plusSeconds(60));
    }
}
