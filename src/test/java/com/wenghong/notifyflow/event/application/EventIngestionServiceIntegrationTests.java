package com.wenghong.notifyflow.event.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;
import com.wenghong.notifyflow.delivery.messaging.DeliveryRequestedEventListener;
import com.wenghong.notifyflow.delivery.messaging.DeliveryJobPublisher;
import com.wenghong.notifyflow.delivery.messaging.outbox.DeliveryOutboxMessage;
import com.wenghong.notifyflow.delivery.messaging.outbox.DeliveryOutboxRepository;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.domain.WebhookSubscription;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.endpoint.persistence.WebhookSubscriptionRepository;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import com.wenghong.notifyflow.event.domain.Event;
import com.wenghong.notifyflow.event.persistence.EventRepository;
import com.wenghong.notifyflow.event.cache.EventIdempotencyCacheRequestedEventListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, EventIngestionService.class,
        DeliveryRequestedEventListener.class,
        EventIdempotencyCacheRequestedEventListener.class,
        EventIngestionServiceIntegrationTests.FixedClockConfiguration.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class EventIngestionServiceIntegrationTests {

    private static final String TEST_SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static final Instant ACCEPTED_AT = Instant.parse("2026-10-29T10:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private EventIngestionService service;

    @MockitoBean
    private DeliveryJobPublisher publisher;

    @MockitoBean
    private EventIdempotencyCache idempotencyCache;

    @MockitoSpyBean
    private DeliveryOutboxRepository outbox;

    @Autowired
    private TransactionTemplate transactions;

    @MockitoSpyBean
    private EventRepository events;

    @MockitoSpyBean
    private DeliveryRepository deliveries;

    @Autowired
    private WebhookEndpointRepository endpoints;

    @Autowired
    private WebhookSubscriptionRepository subscriptions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired private TenantRepository tenants;
    private Tenant tenant;
    private UUID tenantId;
    private TenantAccess access;

    @BeforeEach
    void createOwningTenant() {
        tenant = tenants.saveAndFlush(new Tenant("Company A", ACCEPTED_AT));
        tenantId = tenant.getId();
        access = new TenantAccess(tenantId, TenantRole.ADMIN);
    }

    @AfterEach
    void cleanUpCommittedRows() {
        // This test's PostgreSQL container is isolated from the development database.
        // Delete dependents first because the test deliberately commits its transactions.
        outbox.deleteAllInBatch();
        deliveries.deleteAllInBatch();
        events.deleteAllInBatch();
        subscriptions.deleteAllInBatch();
        endpoints.deleteAllInBatch();
        tenants.deleteAllInBatch();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deliveryAndOutboxRequestCommitTogetherWithoutPublishingToRabbitMq() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        subscriptions.saveAndFlush(new WebhookSubscription(warehouse, "order.confirmed"));

        UUID deliveryId = transactions.execute(status -> {
            service.ingest(access, "order.confirmed", JSON.readTree("{\"orderNumber\":\"128\"}"),
                    "publish-after-commit");
            Delivery delivery = deliveries.findAll().getFirst();
            assertNotNull(delivery.getId());
            // Ingestion has returned, but its enclosing transaction has not committed.
            assertEquals(0L, outbox.count());
            verifyNoInteractions(publisher);
            verify(idempotencyCache, never()).put(any(UUID.class), anyString(), any(EventSnapshot.class));
            return delivery.getId();
        });

        assertNotNull(deliveryId);
        assertTrue(deliveries.findById(deliveryId).isPresent());
        assertPendingOutboxMessages(deliveryId);
        EventSnapshot committed = EventSnapshot.from(events.findByTenant_IdAndIdempotencyKey(tenantId, "publish-after-commit").orElseThrow());
        verify(idempotencyCache).put(tenantId, "publish-after-commit", committed);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollbackDiscardsDeliveryAndItsPendingOutboxRequest() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        subscriptions.saveAndFlush(new WebhookSubscription(warehouse, "order.confirmed"));

        transactions.executeWithoutResult(status -> {
            service.ingest(access, "order.confirmed", JSON.readTree("{\"orderNumber\":\"128\"}"),
                    "rollback-after-announcement");
            // The delivery and its Spring event already exist inside this transaction.
            assertEquals(1L, deliveries.count());
            verifyNoInteractions(publisher);
            status.setRollbackOnly();
        });

        verifyNoInteractions(publisher);
        assertEquals(0L, events.count());
        assertEquals(0L, deliveries.count());
        assertEquals(0L, outbox.count());
        assertEquals(1L, subscriptions.count());
        verify(idempotencyCache, never()).put(any(UUID.class), anyString(), any(EventSnapshot.class));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void ingestWithoutMatchingEnabledSubscriptionsCreatesNoOutboxRequests() {
        WebhookEndpoint disabled = saveEndpoint("Disabled", false);
        WebhookEndpoint unrelated = saveEndpoint("Unrelated", true);
        subscriptions.saveAllAndFlush(List.of(
                new WebhookSubscription(disabled, "order.confirmed"),
                new WebhookSubscription(unrelated, "payment.failed")));

        service.ingest(access, "order.confirmed", JSON.readTree("{\"orderNumber\":\"128\"}"),
                "no-delivery-jobs");

        assertEquals(1L, events.count());
        assertEquals(0L, deliveries.count());
        assertEquals(0L, outbox.count());
        verifyNoInteractions(publisher);
        EventSnapshot committed = EventSnapshot.from(events.findByTenant_IdAndIdempotencyKey(tenantId, "no-delivery-jobs").orElseThrow());
        verify(idempotencyCache).put(tenantId, "no-delivery-jobs", committed);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentConflictingRequestsAcceptOneAndRejectTheOther() throws Exception {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        subscriptions.saveAndFlush(new WebhookSubscription(warehouse, "order.confirmed"));
        String key = "concurrent-conflicting-request";
        List<String> payloads = List.of(
                "{\"orderNumber\":\"128\"}", "{\"orderNumber\":\"999\"}");
        CyclicBarrier initialLookups = new CyclicBarrier(2);
        AtomicInteger lookupCount = new AtomicInteger();

        doAnswer(invocation -> {
            List<UUID> ids = jdbcTemplate.query(
                    "SELECT id FROM events WHERE tenant_id = ? AND idempotency_key = ?",
                    (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), tenantId, key);
            Optional<Event> result = ids.isEmpty()
                    ? Optional.empty() : events.findById(ids.getFirst());
            if (lookupCount.incrementAndGet() <= 2) {
                assertTrue(result.isEmpty());
                initialLookups.await(15, TimeUnit.SECONDS);
            }
            return result;
        }).when(events).findByTenant_IdAndIdempotencyKey(tenantId, key);

        List<EventSnapshot> successes = new ArrayList<>();
        List<JsonNode> successfulPayloads = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<EventSnapshot>> requests = List.of(
                    executor.submit(() -> service.ingest(access,
                            "order.confirmed", JSON.readTree(payloads.get(0)), key)),
                    executor.submit(() -> service.ingest(access,
                            "order.confirmed", JSON.readTree(payloads.get(1)), key)));

            // Observe both results without assuming which request wins the race.
            for (int index = 0; index < requests.size(); index++) {
                try {
                    successes.add(requests.get(index).get(30, TimeUnit.SECONDS));
                    successfulPayloads.add(JSON.readTree(payloads.get(index)));
                } catch (ExecutionException exception) {
                    failures.add(exception.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Ingestion workers did not finish before cleanup");
        }

        assertEquals(1, successes.size());
        assertEquals(1, failures.size());
        assertInstanceOf(IdempotencyConflictException.class, failures.getFirst());
        assertEquals(3, lookupCount.get());
        verify(events, times(3)).findByTenant_IdAndIdempotencyKey(tenantId, key);
        assertEquals(1L, events.count());
        assertEquals(1L, deliveries.count());
        EventSnapshot winner = successes.getFirst();
        assertNotNull(winner.id());
        Event persisted = events.findById(winner.id()).orElseThrow();
        assertEquals(key, persisted.getIdempotencyKey());
        assertEquals("order.confirmed", persisted.getEventType());
        assertEquals(successfulPayloads.getFirst(), winner.payload());
        assertEquals(successfulPayloads.getFirst(), persisted.getPayload());
        assertEquals(ACCEPTED_AT, persisted.getCreatedAt());
        Delivery delivery = deliveries.findAll().getFirst();
        assertEquals(winner.id(), delivery.getEvent().getId());
        assertEquals(warehouse.getId(), delivery.getEndpoint().getId());
        assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
        assertEquals(ACCEPTED_AT, delivery.getCreatedAt());
        assertPendingOutboxMessages(delivery.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentIdenticalRequestsCreateOneEventAndOneDelivery() throws Exception {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        subscriptions.saveAndFlush(new WebhookSubscription(warehouse, "order.confirmed"));
        String key = "concurrent-identical-request";
        String payloadJson = "{\"orderNumber\":\"128\"}";
        CyclicBarrier initialLookups = new CyclicBarrier(2);
        AtomicInteger lookupCount = new AtomicInteger();

        doAnswer(invocation -> {
            // Use real SQL for this controlled lookup, then the real repository to load
            // any event found. This avoids calling an abstract query method through
            // Mockito's callRealMethod() on a Spring Data repository proxy.
            List<UUID> ids = jdbcTemplate.query(
                    "SELECT id FROM events WHERE tenant_id = ? AND idempotency_key = ?",
                    (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), tenantId, key);
            Optional<Event> result = ids.isEmpty()
                    ? Optional.empty() : events.findById(ids.getFirst());

            if (lookupCount.incrementAndGet() <= 2) {
                assertTrue(result.isEmpty());
                // Neither request can insert until both have observed the missing key.
                initialLookups.await(15, TimeUnit.SECONDS);
            }
            // The losing request's recovery lookup must not wait at the barrier.
            return result;
        }).when(events).findByTenant_IdAndIdempotencyKey(tenantId, key);

        EventSnapshot first;
        EventSnapshot second;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<EventSnapshot> firstRequest = executor.submit(
                    () -> service.ingest(access, "order.confirmed", JSON.readTree(payloadJson), key));
            Future<EventSnapshot> secondRequest = executor.submit(
                    () -> service.ingest(access, "order.confirmed", JSON.readTree(payloadJson), key));

            first = firstRequest.get(30, TimeUnit.SECONDS);
            second = secondRequest.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Ingestion workers did not finish before cleanup");
        }

        assertNotNull(first.id());
        assertEquals(first.id(), second.id());
        assertEquals(ACCEPTED_AT, first.createdAt());
        assertEquals(ACCEPTED_AT, second.createdAt());
        // Two initial misses plus one recovery read proves the race path was reached.
        assertEquals(3, lookupCount.get());
        verify(events, times(3)).findByTenant_IdAndIdempotencyKey(tenantId, key);
        assertEquals(1L, events.count());
        assertEquals(1L, deliveries.count());
        Event persisted = events.findById(first.id()).orElseThrow();
        assertEquals(key, persisted.getIdempotencyKey());
        assertEquals("order.confirmed", persisted.getEventType());
        assertEquals(JSON.readTree(payloadJson), persisted.getPayload());
        Delivery delivery = deliveries.findAll().getFirst();
        assertEquals(first.id(), delivery.getEvent().getId());
        assertEquals(warehouse.getId(), delivery.getEndpoint().getId());
        assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
        assertEquals(ACCEPTED_AT, delivery.getCreatedAt());
        assertPendingOutboxMessages(delivery.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reusedKeyWithDifferentEventTypeThrowsConflictWithoutChangingStoredData() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        subscriptions.saveAndFlush(new WebhookSubscription(warehouse, "order.confirmed"));
        JsonNode payload = JSON.readTree("{\"orderNumber\":\"128\"}");
        EventSnapshot original = service.ingest(access, "order.confirmed", payload, "type-conflict-request");
        Delivery originalDelivery = deliveries.findAll().getFirst();

        assertThrows(IdempotencyConflictException.class,
                () -> service.ingest(access, "payment.failed", payload, "type-conflict-request"));

        assertEquals(1L, events.count());
        assertEquals(1L, deliveries.count());
        Event persisted = events.findByTenant_IdAndIdempotencyKey(tenantId, "type-conflict-request").orElseThrow();
        assertEquals(original.id(), persisted.getId());
        assertEquals("order.confirmed", persisted.getEventType());
        assertEquals(payload, persisted.getPayload());
        assertEquals(ACCEPTED_AT, persisted.getCreatedAt());
        Delivery remainingDelivery = deliveries.findAll().getFirst();
        assertEquals(originalDelivery.getId(), remainingDelivery.getId());
        assertEquals(original.id(), remainingDelivery.getEvent().getId());
        assertEquals(warehouse.getId(), remainingDelivery.getEndpoint().getId());
        assertEquals(DeliveryStatus.PENDING, remainingDelivery.getStatus());
        assertEquals(ACCEPTED_AT, remainingDelivery.getCreatedAt());
        assertPendingOutboxMessages(originalDelivery.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reusedKeyWithDifferentPayloadThrowsConflictWithoutChangingStoredData() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        subscriptions.saveAndFlush(new WebhookSubscription(warehouse, "order.confirmed"));
        JsonNode originalPayload = JSON.readTree("{\"orderNumber\":\"128\"}");
        JsonNode changedPayload = JSON.readTree("{\"orderNumber\":\"999\"}");
        EventSnapshot original = service.ingest(access, "order.confirmed", originalPayload, "conflict-request");
        Delivery originalDelivery = deliveries.findAll().getFirst();

        assertThrows(IdempotencyConflictException.class,
                () -> service.ingest(access, "order.confirmed", changedPayload, "conflict-request"));

        assertEquals(1L, events.count());
        assertEquals(1L, deliveries.count());
        Event persisted = events.findByTenant_IdAndIdempotencyKey(tenantId, "conflict-request").orElseThrow();
        assertEquals(original.id(), persisted.getId());
        assertEquals("order.confirmed", persisted.getEventType());
        assertEquals(originalPayload, persisted.getPayload());
        assertEquals(ACCEPTED_AT, persisted.getCreatedAt());
        Delivery remainingDelivery = deliveries.findAll().getFirst();
        assertEquals(originalDelivery.getId(), remainingDelivery.getId());
        assertEquals(original.id(), remainingDelivery.getEvent().getId());
        assertEquals(warehouse.getId(), remainingDelivery.getEndpoint().getId());
        assertEquals(DeliveryStatus.PENDING, remainingDelivery.getStatus());
        assertEquals(ACCEPTED_AT, remainingDelivery.getCreatedAt());
        assertPendingOutboxMessages(originalDelivery.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void repeatedRequestReturnsExistingEventWithoutCreatingMoreDeliveries() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        subscriptions.saveAndFlush(new WebhookSubscription(warehouse, "order.confirmed"));
        JsonNode originalPayload = JSON.readTree("""
                {"orderNumber":"128","customer":{"id":"customer-42"}}
                """);
        // Reordered keys and different whitespace still represent the same request data.
        JsonNode retryPayload = JSON.readTree("""
                {
                  "customer": { "id": "customer-42" },
                  "orderNumber": "128"
                }
                """);

        EventSnapshot original = service.ingest(access, "order.confirmed", originalPayload, "retry-request");
        Delivery originalDelivery = deliveries.findAll().getFirst();

        EventSnapshot retried = service.ingest(access, "order.confirmed", retryPayload, "retry-request");

        assertEquals(original.id(), retried.id());
        assertEquals(ACCEPTED_AT, retried.createdAt());
        assertEquals(originalPayload, retried.payload());
        assertEquals(1L, events.count());
        assertEquals(1L, deliveries.count());
        Event persisted = events.findByTenant_IdAndIdempotencyKey(tenantId, "retry-request").orElseThrow();
        assertEquals(original.id(), persisted.getId());
        assertEquals(original.createdAt(), persisted.getCreatedAt());
        Delivery remainingDelivery = deliveries.findAll().getFirst();
        assertEquals(originalDelivery.getId(), remainingDelivery.getId());
        assertEquals(original.id(), remainingDelivery.getEvent().getId());
        assertEquals(warehouse.getId(), remainingDelivery.getEndpoint().getId());
        assertEquals(DeliveryStatus.PENDING, remainingDelivery.getStatus());
        assertEquals(ACCEPTED_AT, remainingDelivery.getCreatedAt());
        assertPendingOutboxMessages(originalDelivery.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void ingestRollsBackEventWhenSavingDeliveriesFails() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        subscriptions.saveAndFlush(new WebhookSubscription(warehouse, "order.confirmed"));
        IllegalStateException failure = new IllegalStateException("Simulated delivery storage failure");
        doThrow(failure).when(deliveries).saveAll(anyList());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.ingest(access, "order.confirmed",
                        JSON.readTree("{\"orderNumber\":\"128\"}"), "rollback-request"));

        assertSame(failure, thrown);
        verify(deliveries).saveAll(anyList());
        assertTrue(events.findByTenant_IdAndIdempotencyKey(tenantId, "rollback-request").isEmpty());
        assertEquals(0L, events.count());
        assertEquals(0L, deliveries.count());
        // Setup was committed separately and survives the failed ingestion transaction.
        assertEquals(1L, subscriptions.count());
        assertEquals(0L, outbox.count());
        verifyNoInteractions(publisher);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void ingestPersistsEventAndPendingDeliveriesForMatchingEnabledEndpoints() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        WebhookEndpoint email = saveEndpoint("Email", true);
        WebhookEndpoint finance = saveEndpoint("Finance", true);
        WebhookEndpoint disabled = saveEndpoint("Disabled", false);
        subscriptions.saveAllAndFlush(List.of(
                new WebhookSubscription(warehouse, "order.confirmed"),
                new WebhookSubscription(email, "order.confirmed"),
                new WebhookSubscription(finance, "payment.failed"),
                new WebhookSubscription(disabled, "order.confirmed")));
        JsonNode payload = JSON.readTree("""
                {"orderNumber":"128","items":[{"sku":"A","quantity":2}]}
                """);

        // There is no test transaction: ingest must commit its own transaction.
        EventSnapshot accepted = service.ingest(access, "order.confirmed", payload, "order-128-confirmed");

        assertNotNull(accepted);
        assertNotNull(accepted.id());
        assertEquals(1L, events.count());
        Event persisted = events.findById(accepted.id()).orElseThrow();
        assertEquals("order.confirmed", persisted.getEventType());
        assertEquals("order-128-confirmed", persisted.getIdempotencyKey());
        assertEquals(payload, persisted.getPayload());
        assertEquals(ACCEPTED_AT, persisted.getCreatedAt());

        List<Delivery> persistedDeliveries = deliveries.findAll();
        assertEquals(2, persistedDeliveries.size());
        assertEquals(Set.of(warehouse.getId(), email.getId()), persistedDeliveries.stream()
                .map(delivery -> delivery.getEndpoint().getId())
                .collect(Collectors.toSet()));
        for (Delivery delivery : persistedDeliveries) {
            assertNotNull(delivery.getId());
            assertEquals(persisted.getId(), delivery.getEvent().getId());
            assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
            assertEquals(ACCEPTED_AT, delivery.getCreatedAt());
        }
        assertPendingOutboxMessages(persistedDeliveries.stream().map(Delivery::getId).toArray(UUID[]::new));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void outboxStorageFailureRollsBackEventAndDeliveries() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        subscriptions.saveAndFlush(new WebhookSubscription(warehouse, "order.confirmed"));
        IllegalStateException failure = new IllegalStateException("Simulated outbox storage failure");
        doThrow(failure).when(outbox).save(any(DeliveryOutboxMessage.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.ingest(access, "order.confirmed", JSON.readTree("{\"orderNumber\":\"128\"}"),
                        "outbox-storage-failure"));

        assertSame(failure, thrown);
        assertEquals(0L, events.count());
        assertEquals(0L, deliveries.count());
        assertEquals(0L, outbox.count());
        assertEquals(1L, subscriptions.count());
        verifyNoInteractions(publisher);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void existingDatabaseEventRepopulatesCacheWithoutCreatingMoreRows() {
        JsonNode payload = JSON.readTree("{\"number\":128}");
        Event stored = events.saveAndFlush(new Event(tenant, "order.confirmed", payload, "existing-cache-miss", ACCEPTED_AT));

        EventSnapshot result = service.ingest(access, "order.confirmed", payload, "existing-cache-miss");

        assertEquals(stored.getId(), result.id());
        verify(idempotencyCache).put(tenantId, "existing-cache-miss", result);
        assertEquals(1L, events.count());
        assertEquals(0L, deliveries.count());
        assertEquals(0L, outbox.count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void existingEventReadInsideRolledBackTransactionDoesNotPopulateCache() {
        JsonNode payload = JSON.readTree("{\"number\":128}");
        events.saveAndFlush(new Event(tenant, "order.confirmed", payload, "existing-rollback", ACCEPTED_AT));

        transactions.executeWithoutResult(status -> {
            service.ingest(access, "order.confirmed", payload, "existing-rollback");
            verify(idempotencyCache, never()).put(any(UUID.class), anyString(), any(EventSnapshot.class));
            status.setRollbackOnly();
        });

        verify(idempotencyCache, never()).put(any(UUID.class), anyString(), any(EventSnapshot.class));
        assertEquals(1L, events.count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void beforeCommitOutboxFailureNeverPopulatesIdempotencyCache() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        subscriptions.saveAndFlush(new WebhookSubscription(warehouse, "order.confirmed"));
        doThrow(new IllegalStateException("Test commit failure"))
                .when(outbox).save(any(DeliveryOutboxMessage.class));

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            // Publication occurs while this outer transaction is still open.
            service.ingest(access, "order.confirmed", JSON.readTree("{}"), "outer-commit-failure");
            verify(idempotencyCache, never()).put(any(UUID.class), anyString(), any(EventSnapshot.class));
        }));

        verify(idempotencyCache, never()).put(any(UUID.class), anyString(), any(EventSnapshot.class));
        assertEquals(0L, events.count());
        assertEquals(0L, deliveries.count());
        assertEquals(0L, outbox.count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void warmCacheRetrySkipsDatabaseLookupAndCreatesNoExtraDeliveries() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        subscriptions.saveAndFlush(new WebhookSubscription(warehouse, "order.confirmed"));
        String key = "warm-cache-retry";
        JsonNode payload = JSON.readTree("{\"number\":128}");
        EventSnapshot first = service.ingest(access, "order.confirmed", payload, key);
        verify(idempotencyCache).put(tenantId, key, first);
        when(idempotencyCache.findByKey(tenantId, key)).thenReturn(Optional.of(first));

        EventSnapshot second = service.ingest(access, "order.confirmed", payload, key);

        assertEquals(first, second);
        verify(events).findByTenant_IdAndIdempotencyKey(tenantId, key);
        verify(idempotencyCache).put(tenantId, key, first);
        assertEquals(1L, events.count());
        assertEquals(1L, deliveries.count());
        assertPendingOutboxMessages(deliveries.findAll().getFirst().getId());
    }

    private void assertPendingOutboxMessages(UUID... deliveryIds) {
        List<DeliveryOutboxMessage> messages = outbox.findAll();
        assertEquals(deliveryIds.length, messages.size());
        assertEquals(Set.of(deliveryIds), messages.stream()
                .map(DeliveryOutboxMessage::getDeliveryId).collect(Collectors.toSet()));
        for (DeliveryOutboxMessage message : messages) {
            assertNotNull(message.getId());
            assertEquals(ACCEPTED_AT, message.getCreatedAt());
            assertNull(message.getPublishedAt());
        }
        verifyNoInteractions(publisher);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sameKeyAcrossTenantsCreatesIndependentEventsAndRoutesOnlyWithinEachTenant() {
        Tenant other = tenants.saveAndFlush(new Tenant("Company B", ACCEPTED_AT));
        TenantAccess otherAccess = new TenantAccess(other.getId(), TenantRole.ADMIN);
        WebhookEndpoint firstEndpoint = saveEndpoint("Company A receiver", true);
        WebhookEndpoint secondEndpoint = endpoints.saveAndFlush(new WebhookEndpoint(other,
                "Company B receiver", "https://example.com/b", ACCEPTED_AT, TEST_SIGNING_SECRET));
        subscriptions.saveAllAndFlush(List.of(
                new WebhookSubscription(firstEndpoint, "order.confirmed"),
                new WebhookSubscription(secondEndpoint, "order.confirmed")));
        String key = "same-key-two-tenants";
        JsonNode firstPayload = JSON.readTree("{\"company\":\"A\",\"orderId\":128}");
        JsonNode secondPayload = JSON.readTree("{\"company\":\"B\",\"orderId\":256}");

        EventSnapshot first = service.ingest(access, "order.confirmed", firstPayload, key);
        // A warm cache for Company A must not answer Company B's request.
        when(idempotencyCache.findByKey(tenantId, key)).thenReturn(Optional.of(first));
        EventSnapshot second = service.ingest(otherAccess, "order.confirmed", secondPayload, key);

        org.junit.jupiter.api.Assertions.assertNotEquals(first.id(), second.id());
        assertEquals(2L, events.count());
        assertEquals(2L, deliveries.count());
        assertEquals(tenantId, jdbcTemplate.queryForObject("SELECT tenant_id FROM events WHERE id = ?", UUID.class, first.id()));
        assertEquals(other.getId(), jdbcTemplate.queryForObject("SELECT tenant_id FROM events WHERE id = ?", UUID.class, second.id()));
        for (Delivery delivery : deliveries.findAll()) {
            UUID expectedEndpoint = delivery.getEvent().getId().equals(first.id())
                    ? firstEndpoint.getId() : secondEndpoint.getId();
            assertEquals(expectedEndpoint, delivery.getEndpoint().getId());
        }

        // With no Company B cache entry, PostgreSQL must still reuse only its own event.
        assertEquals(second, service.ingest(otherAccess, "order.confirmed", secondPayload, key));
        when(idempotencyCache.findByKey(other.getId(), key)).thenReturn(Optional.of(second));
        assertEquals(first, service.ingest(access, "order.confirmed", firstPayload, key));
        assertEquals(second, service.ingest(otherAccess, "order.confirmed", secondPayload, key));
        assertThrows(IdempotencyConflictException.class,
                () -> service.ingest(otherAccess, "order.confirmed", firstPayload, key));
        assertEquals(2L, events.count());
        assertEquals(2L, deliveries.count());
        verify(idempotencyCache).put(tenantId, key, first);
        verify(idempotencyCache, times(2)).put(other.getId(), key, second);
        assertPendingOutboxMessages(deliveries.findAll().stream().map(Delivery::getId).toArray(UUID[]::new));
    }

    private WebhookEndpoint saveEndpoint(String name, boolean enabled) {
        Instant createdAt = ACCEPTED_AT.minusSeconds(60);
        WebhookEndpoint endpoint = new WebhookEndpoint(tenant,
                name, "https://example.com/webhooks", createdAt, TEST_SIGNING_SECRET);
        endpoint.changeEnabled(enabled, createdAt);
        return endpoints.saveAndFlush(endpoint);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC);
        }
    }
}
