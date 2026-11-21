package com.wenghong.notifyflow.event.application;

import com.wenghong.notifyflow.identity.domain.Tenant;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import com.wenghong.notifyflow.endpoint.persistence.WebhookSubscriptionRepository;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import com.wenghong.notifyflow.event.domain.Event;
import com.wenghong.notifyflow.identity.security.ApiKeyPrincipal;
import com.wenghong.notifyflow.event.persistence.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class EventIngestionServiceTests {

    private final Tenant fixtureTenant = new Tenant("Fixture workspace", Instant.parse("2026-11-20T00:00:00Z"));

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final TenantAccess ACCESS = new TenantAccess(TENANT_ID, TenantRole.ADMIN);
    private static final UUID ID = UUID.randomUUID();
    private final EventRepository events = mock(EventRepository.class);
    private final WebhookSubscriptionRepository subscriptions = mock(WebhookSubscriptionRepository.class);
    private final DeliveryRepository deliveries = mock(DeliveryRepository.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final EventIdempotencyCache cache = mock(EventIdempotencyCache.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final EventIngestionService service = new EventIngestionService(events, subscriptions, deliveries,
            Clock.fixed(NOW, ZoneOffset.UTC), transactions, publisher, cache, tenants);

    @Test
    void viewerIsDeniedBeforeCacheOrDatabaseAccess() {
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.ingest(new TenantAccess(TENANT_ID, TenantRole.VIEWER),
                        "order.confirmed", snapshot().payload(), "request-1"));
        verifyNoInteractions(cache, events, subscriptions, deliveries, transactions, publisher, tenants);
    }

    @Test
    void matchingCacheHitReusesOriginalSnapshotWithoutDatabaseOrCacheWriteRequest() {
        EventSnapshot snapshot = snapshot();
        when(cache.findByKey(TENANT_ID, "request-1")).thenReturn(Optional.of(snapshot));

        EventSnapshot result = service.ingest(ACCESS, "order.confirmed",
                JSON.readTree("{\"customer\":{\"id\":42},\"number\":128}"), "request-1");

        assertSame(snapshot, result);
        verify(cache).findByKey(TENANT_ID, "request-1");
        verifyNoMoreInteractions(cache);
        verifyNoInteractions(events, subscriptions, deliveries, transactions, publisher);
    }

    @ParameterizedTest
    @CsvSource({"payment.failed,128", "order.confirmed,999", "payment.failed,999"})
    void conflictingCacheHitRejectsWithoutDatabaseAccess(String type, int number) {
        when(cache.findByKey(TENANT_ID, "request-1")).thenReturn(Optional.of(snapshot()));

        assertThrows(IdempotencyConflictException.class, () -> service.ingest(ACCESS, type,
                JSON.readTree("{\"number\":" + number + ",\"customer\":{\"id\":42}}"), "request-1"));

        verifyNoInteractions(events, subscriptions, deliveries, transactions, publisher);
    }

    @Test
    void cacheMissReusesDatabaseEventAndRequestsCachePopulation() {
        Event stored = new Event(fixtureTenant, "order.confirmed", snapshot().payload(), "request-1", NOW);
        ReflectionTestUtils.setField(stored, "id", ID);
        when(cache.findByKey(TENANT_ID, "request-1")).thenReturn(Optional.empty());
        when(events.findByTenant_IdAndIdempotencyKey(TENANT_ID, "request-1")).thenReturn(Optional.of(stored));

        EventSnapshot result = service.ingest(ACCESS, "order.confirmed", snapshot().payload(), "request-1");

        assertEquals(snapshot(), result);
        verify(events).findByTenant_IdAndIdempotencyKey(TENANT_ID, "request-1");
        verify(publisher).publishEvent(new EventIdempotencyCacheRequestedEvent(TENANT_ID, "request-1", result));
        verifyNoInteractions(subscriptions, deliveries, transactions);
        verify(cache).findByKey(TENANT_ID, "request-1");
        verifyNoMoreInteractions(cache);
    }

    @Test
    void conflictingDatabaseEventDoesNotRequestCacheWrite() {
        Event stored = new Event(fixtureTenant, "order.confirmed", snapshot().payload(), "request-1", NOW);
        when(cache.findByKey(TENANT_ID, "request-1")).thenReturn(Optional.empty());
        when(events.findByTenant_IdAndIdempotencyKey(TENANT_ID, "request-1")).thenReturn(Optional.of(stored));

        assertThrows(IdempotencyConflictException.class,
                () -> service.ingest(ACCESS, "payment.failed", snapshot().payload(), "request-1"));

        verifyNoInteractions(publisher, subscriptions, deliveries, transactions);
    }

    @Test
    void databaseFailurePropagatesWithoutRequestingCacheWrite() {
        var failure = new DataAccessResourceFailureException("Test database failure");
        when(cache.findByKey(TENANT_ID, "request-1")).thenReturn(Optional.empty());
        when(events.findByTenant_IdAndIdempotencyKey(TENANT_ID, "request-1")).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.ingest(ACCESS, "order.confirmed", snapshot().payload(), "request-1")));

        verifyNoInteractions(publisher, subscriptions, deliveries, transactions);
    }

    private EventSnapshot snapshot() {
        return new EventSnapshot(ID, "order.confirmed",
                JSON.readTree("{\"number\":128,\"customer\":{\"id\":42}}"), NOW);
    }

    @Test
    void producerUsesPrincipalTenantForCacheLookupWithoutMembershipOrDatabaseRead() {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(UUID.randomUUID(), TENANT_ID);
        EventSnapshot snapshot = snapshot();
        when(cache.findByKey(TENANT_ID, "request-1")).thenReturn(Optional.of(snapshot));

        assertSame(snapshot, service.ingestForProducer(principal, "order.confirmed", snapshot.payload(), "request-1"));
        verify(cache).findByKey(TENANT_ID, "request-1");
        verifyNoInteractions(events, tenants, subscriptions, deliveries, transactions, publisher);
    }

    @Test
    void producerDatabaseRetryRequestsCacheWriteForPrincipalTenant() {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(UUID.randomUUID(), TENANT_ID);
        Event stored = new Event(fixtureTenant, "order.confirmed", snapshot().payload(), "request-1", NOW);
        ReflectionTestUtils.setField(stored, "id", ID);
        when(events.findByTenant_IdAndIdempotencyKey(TENANT_ID, "request-1")).thenReturn(Optional.of(stored));

        EventSnapshot result = service.ingestForProducer(principal, "order.confirmed", snapshot().payload(), "request-1");
        assertEquals(ID, result.id());
        verify(events).findByTenant_IdAndIdempotencyKey(TENANT_ID, "request-1");
        verify(publisher).publishEvent(new EventIdempotencyCacheRequestedEvent(TENANT_ID, "request-1", result));
        verifyNoInteractions(subscriptions, deliveries, transactions, tenants);
    }

    @Test
    void producerConflictingCachedContentStillRejectsBeforeDatabaseAccess() {
        when(cache.findByKey(TENANT_ID, "request-1")).thenReturn(Optional.of(snapshot()));
        assertThrows(IdempotencyConflictException.class, () -> service.ingestForProducer(
                new ApiKeyPrincipal(UUID.randomUUID(), TENANT_ID), "payment.failed", snapshot().payload(), "request-1"));
        verifyNoInteractions(events, tenants, subscriptions, deliveries, transactions, publisher);
    }
}
