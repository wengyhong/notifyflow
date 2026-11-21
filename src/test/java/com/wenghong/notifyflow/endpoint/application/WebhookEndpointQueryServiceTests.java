package com.wenghong.notifyflow.endpoint.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.cache.RedisWebhookEndpointCache;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
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

    private static final UUID TENANT_ID = UUID.fromString("e8d0c4f9-28df-4070-a6a4-052a05c68236");

    private static final UUID ID = UUID.fromString("1990fcb2-3db0-4664-b949-3892111ba544");
    private static final TenantAccess ACCESS = new TenantAccess(TENANT_ID, TenantRole.ADMIN);
    private static final Instant CREATED = Instant.parse("2026-11-18T00:00:00Z");
    private final WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
    private final WebhookEndpointCache cache = mock(WebhookEndpointCache.class);
    private final WebhookEndpointQueryService service = new WebhookEndpointQueryService(repository, cache);

    @ParameterizedTest
    @EnumSource(TenantRole.class)
    void bothMembershipRolesCanReadMatchingCachedEndpoint(TenantRole role) {
        WebhookEndpointSnapshot snapshot = snapshot(true);
        when(cache.findById(ID)).thenReturn(Optional.of(snapshot));

        assertSame(snapshot, service.findById(new TenantAccess(TENANT_ID, role), ID).orElseThrow());
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void foreignOrUnownedCachedEndpointCannotGrantAccess(boolean missingOwner) {
        WebhookEndpointSnapshot invalid = new WebhookEndpointSnapshot(ID,
                missingOwner ? null : UUID.randomUUID(), "Other company", "https://example.com/private",
                true, CREATED, CREATED);
        when(cache.findById(ID)).thenReturn(Optional.of(invalid));
        when(repository.findByIdAndTenant_Id(ID, TENANT_ID)).thenReturn(Optional.empty());

        assertTrue(service.findById(ACCESS, ID).isEmpty());
        verify(repository).findByIdAndTenant_Id(ID, TENANT_ID);
        verify(cache, never()).put(any());
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void foreignOrUnownedCacheEntryFallsBackToVerifiedDatabaseOwner(boolean missingOwner) {
        WebhookEndpointSnapshot invalid = new WebhookEndpointSnapshot(ID,
                missingOwner ? null : UUID.randomUUID(), "Stale", "https://example.com/stale",
                false, CREATED, CREATED);
        when(cache.findById(ID)).thenReturn(Optional.of(invalid));
        WebhookEndpoint endpoint = endpoint(true);
        when(repository.findByIdAndTenant_Id(ID, TENANT_ID)).thenReturn(Optional.of(endpoint));

        assertEquals(snapshot(true), service.findById(ACCESS, ID).orElseThrow());
        verify(repository).findByIdAndTenant_Id(ID, TENANT_ID);
        verify(cache).put(snapshot(true));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void cacheHitReturnsSnapshotWithoutLoadingPostgresOrRewritingCache() {
        WebhookEndpointSnapshot snapshot = snapshot(false);
        when(cache.findById(ID)).thenReturn(Optional.of(snapshot));

        assertSame(snapshot, service.findById(ACCESS, ID).orElseThrow());

        verify(cache).findById(ID);
        verifyNoMoreInteractions(cache);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cacheMissLoadsAllPublicFieldsAndCachesSnapshot(boolean enabled) {
        when(cache.findById(ID)).thenReturn(Optional.empty());
        WebhookEndpoint endpoint = endpoint(enabled);
        when(repository.findByIdAndTenant_Id(ID, TENANT_ID)).thenReturn(Optional.of(endpoint));

        assertEquals(snapshot(enabled), service.findById(ACCESS, ID).orElseThrow());

        verify(repository).findByIdAndTenant_Id(ID, TENANT_ID);
        verify(cache).findById(ID);
        verify(cache).put(snapshot(enabled));
        verifyNoMoreInteractions(cache, repository);
    }

    @Test
    void missingEndpointReturnsEmptyWithoutCachingAbsence() {
        when(cache.findById(ID)).thenReturn(Optional.empty());
        when(repository.findByIdAndTenant_Id(ID, TENANT_ID)).thenReturn(Optional.empty());

        assertTrue(service.findById(ACCESS, ID).isEmpty());

        verify(repository).findByIdAndTenant_Id(ID, TENANT_ID);
        verify(cache).findById(ID);
        verifyNoMoreInteractions(cache, repository);
    }

    @Test
    void databaseFailurePropagatesWithoutWritingCache() {
        when(cache.findById(ID)).thenReturn(Optional.empty());
        var failure = new DataAccessResourceFailureException("Test PostgreSQL failure");
        when(repository.findByIdAndTenant_Id(ID, TENANT_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.findById(ACCESS, ID)));

        verify(cache, never()).put(any(WebhookEndpointSnapshot.class));
    }

    @Test
    void redisReadAndWriteFailuresStillReturnPostgresSnapshot() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("Test Redis outage"));
        var actualCache = new RedisWebhookEndpointCache(redis, JsonMapper.builder().build());
        var query = new WebhookEndpointQueryService(repository, actualCache);
        WebhookEndpoint endpoint = endpoint(true);
        when(repository.findByIdAndTenant_Id(ID, TENANT_ID)).thenReturn(Optional.of(endpoint));

        assertEquals(snapshot(true), query.findById(ACCESS, ID).orElseThrow());

        verify(repository).findByIdAndTenant_Id(ID, TENANT_ID);
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
        when(repository.findByIdAndTenant_Id(ID, TENANT_ID)).thenReturn(Optional.of(endpoint));
        String key = "notifyflow:endpoint:" + ID;
        doThrow(new RedisConnectionFailureException("Test write failure"))
                .when(values).set(key, json.writeValueAsString(snapshot(true)), Duration.ofSeconds(60));

        assertEquals(snapshot(true), query.findById(ACCESS, ID).orElseThrow());

        verify(values).get(key);
        verify(values).set(key, json.writeValueAsString(snapshot(true)), Duration.ofSeconds(60));
    }

    private WebhookEndpoint endpoint(boolean enabled) {
        WebhookEndpoint endpoint = mock(WebhookEndpoint.class);
        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(TENANT_ID);
        when(endpoint.getTenant()).thenReturn(tenant);
        when(endpoint.getId()).thenReturn(ID);
        when(endpoint.getName()).thenReturn("Warehouse");
        when(endpoint.getUrl()).thenReturn("https://example.com/webhook");
        when(endpoint.isEnabled()).thenReturn(enabled);
        when(endpoint.getCreatedAt()).thenReturn(CREATED);
        when(endpoint.getUpdatedAt()).thenReturn(CREATED.plusSeconds(60));
        return endpoint;
    }

    private WebhookEndpointSnapshot snapshot(boolean enabled) {
        return new WebhookEndpointSnapshot(ID, TENANT_ID, "Warehouse", "https://example.com/webhook",
                enabled, CREATED, CREATED.plusSeconds(60));
    }
}
