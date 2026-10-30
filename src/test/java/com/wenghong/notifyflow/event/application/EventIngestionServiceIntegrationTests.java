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
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.domain.WebhookSubscription;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.endpoint.persistence.WebhookSubscriptionRepository;
import com.wenghong.notifyflow.event.domain.Event;
import com.wenghong.notifyflow.event.persistence.EventRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, EventIngestionService.class,
        EventIngestionServiceIntegrationTests.FixedClockConfiguration.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class EventIngestionServiceIntegrationTests {

    private static final Instant ACCEPTED_AT = Instant.parse("2026-10-29T10:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private EventIngestionService service;

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

    @AfterEach
    void cleanUpCommittedRows() {
        // This test's PostgreSQL container is isolated from the development database.
        // Delete dependents first because the test deliberately commits its transactions.
        deliveries.deleteAllInBatch();
        events.deleteAllInBatch();
        subscriptions.deleteAllInBatch();
        endpoints.deleteAllInBatch();
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
                    "SELECT id FROM events WHERE idempotency_key = ?",
                    (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), key);
            Optional<Event> result = ids.isEmpty()
                    ? Optional.empty() : events.findById(ids.getFirst());
            if (lookupCount.incrementAndGet() <= 2) {
                assertTrue(result.isEmpty());
                initialLookups.await(15, TimeUnit.SECONDS);
            }
            return result;
        }).when(events).findByIdempotencyKey(key);

        List<Event> successes = new ArrayList<>();
        List<JsonNode> successfulPayloads = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Event>> requests = List.of(
                    executor.submit(() -> service.ingest(
                            "order.confirmed", JSON.readTree(payloads.get(0)), key)),
                    executor.submit(() -> service.ingest(
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
        verify(events, times(3)).findByIdempotencyKey(key);
        assertEquals(1L, events.count());
        assertEquals(1L, deliveries.count());
        Event winner = successes.getFirst();
        assertNotNull(winner.getId());
        Event persisted = events.findById(winner.getId()).orElseThrow();
        assertEquals(key, persisted.getIdempotencyKey());
        assertEquals("order.confirmed", persisted.getEventType());
        assertEquals(successfulPayloads.getFirst(), winner.getPayload());
        assertEquals(successfulPayloads.getFirst(), persisted.getPayload());
        assertEquals(ACCEPTED_AT, persisted.getCreatedAt());
        Delivery delivery = deliveries.findAll().getFirst();
        assertEquals(winner.getId(), delivery.getEvent().getId());
        assertEquals(warehouse.getId(), delivery.getEndpoint().getId());
        assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
        assertEquals(ACCEPTED_AT, delivery.getCreatedAt());
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
                    "SELECT id FROM events WHERE idempotency_key = ?",
                    (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), key);
            Optional<Event> result = ids.isEmpty()
                    ? Optional.empty() : events.findById(ids.getFirst());

            if (lookupCount.incrementAndGet() <= 2) {
                assertTrue(result.isEmpty());
                // Neither request can insert until both have observed the missing key.
                initialLookups.await(15, TimeUnit.SECONDS);
            }
            // The losing request's recovery lookup must not wait at the barrier.
            return result;
        }).when(events).findByIdempotencyKey(key);

        Event first;
        Event second;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Event> firstRequest = executor.submit(
                    () -> service.ingest("order.confirmed", JSON.readTree(payloadJson), key));
            Future<Event> secondRequest = executor.submit(
                    () -> service.ingest("order.confirmed", JSON.readTree(payloadJson), key));

            first = firstRequest.get(30, TimeUnit.SECONDS);
            second = secondRequest.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Ingestion workers did not finish before cleanup");
        }

        assertNotNull(first.getId());
        assertEquals(first.getId(), second.getId());
        assertEquals(ACCEPTED_AT, first.getCreatedAt());
        assertEquals(ACCEPTED_AT, second.getCreatedAt());
        // Two initial misses plus one recovery read proves the race path was reached.
        assertEquals(3, lookupCount.get());
        verify(events, times(3)).findByIdempotencyKey(key);
        assertEquals(1L, events.count());
        assertEquals(1L, deliveries.count());
        Event persisted = events.findById(first.getId()).orElseThrow();
        assertEquals(key, persisted.getIdempotencyKey());
        assertEquals("order.confirmed", persisted.getEventType());
        assertEquals(JSON.readTree(payloadJson), persisted.getPayload());
        Delivery delivery = deliveries.findAll().getFirst();
        assertEquals(first.getId(), delivery.getEvent().getId());
        assertEquals(warehouse.getId(), delivery.getEndpoint().getId());
        assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
        assertEquals(ACCEPTED_AT, delivery.getCreatedAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reusedKeyWithDifferentEventTypeThrowsConflictWithoutChangingStoredData() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        subscriptions.saveAndFlush(new WebhookSubscription(warehouse, "order.confirmed"));
        JsonNode payload = JSON.readTree("{\"orderNumber\":\"128\"}");
        Event original = service.ingest("order.confirmed", payload, "type-conflict-request");
        Delivery originalDelivery = deliveries.findAll().getFirst();

        assertThrows(IdempotencyConflictException.class,
                () -> service.ingest("payment.failed", payload, "type-conflict-request"));

        assertEquals(1L, events.count());
        assertEquals(1L, deliveries.count());
        Event persisted = events.findByIdempotencyKey("type-conflict-request").orElseThrow();
        assertEquals(original.getId(), persisted.getId());
        assertEquals("order.confirmed", persisted.getEventType());
        assertEquals(payload, persisted.getPayload());
        assertEquals(ACCEPTED_AT, persisted.getCreatedAt());
        Delivery remainingDelivery = deliveries.findAll().getFirst();
        assertEquals(originalDelivery.getId(), remainingDelivery.getId());
        assertEquals(original.getId(), remainingDelivery.getEvent().getId());
        assertEquals(warehouse.getId(), remainingDelivery.getEndpoint().getId());
        assertEquals(DeliveryStatus.PENDING, remainingDelivery.getStatus());
        assertEquals(ACCEPTED_AT, remainingDelivery.getCreatedAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reusedKeyWithDifferentPayloadThrowsConflictWithoutChangingStoredData() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        subscriptions.saveAndFlush(new WebhookSubscription(warehouse, "order.confirmed"));
        JsonNode originalPayload = JSON.readTree("{\"orderNumber\":\"128\"}");
        JsonNode changedPayload = JSON.readTree("{\"orderNumber\":\"999\"}");
        Event original = service.ingest("order.confirmed", originalPayload, "conflict-request");
        Delivery originalDelivery = deliveries.findAll().getFirst();

        assertThrows(IdempotencyConflictException.class,
                () -> service.ingest("order.confirmed", changedPayload, "conflict-request"));

        assertEquals(1L, events.count());
        assertEquals(1L, deliveries.count());
        Event persisted = events.findByIdempotencyKey("conflict-request").orElseThrow();
        assertEquals(original.getId(), persisted.getId());
        assertEquals("order.confirmed", persisted.getEventType());
        assertEquals(originalPayload, persisted.getPayload());
        assertEquals(ACCEPTED_AT, persisted.getCreatedAt());
        Delivery remainingDelivery = deliveries.findAll().getFirst();
        assertEquals(originalDelivery.getId(), remainingDelivery.getId());
        assertEquals(original.getId(), remainingDelivery.getEvent().getId());
        assertEquals(warehouse.getId(), remainingDelivery.getEndpoint().getId());
        assertEquals(DeliveryStatus.PENDING, remainingDelivery.getStatus());
        assertEquals(ACCEPTED_AT, remainingDelivery.getCreatedAt());
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

        Event original = service.ingest("order.confirmed", originalPayload, "retry-request");
        Delivery originalDelivery = deliveries.findAll().getFirst();

        Event retried = service.ingest("order.confirmed", retryPayload, "retry-request");

        assertEquals(original.getId(), retried.getId());
        assertEquals(ACCEPTED_AT, retried.getCreatedAt());
        assertEquals(originalPayload, retried.getPayload());
        assertEquals(1L, events.count());
        assertEquals(1L, deliveries.count());
        Event persisted = events.findByIdempotencyKey("retry-request").orElseThrow();
        assertEquals(original.getId(), persisted.getId());
        assertEquals(original.getCreatedAt(), persisted.getCreatedAt());
        Delivery remainingDelivery = deliveries.findAll().getFirst();
        assertEquals(originalDelivery.getId(), remainingDelivery.getId());
        assertEquals(original.getId(), remainingDelivery.getEvent().getId());
        assertEquals(warehouse.getId(), remainingDelivery.getEndpoint().getId());
        assertEquals(DeliveryStatus.PENDING, remainingDelivery.getStatus());
        assertEquals(ACCEPTED_AT, remainingDelivery.getCreatedAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void ingestRollsBackEventWhenSavingDeliveriesFails() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse", true);
        subscriptions.saveAndFlush(new WebhookSubscription(warehouse, "order.confirmed"));
        IllegalStateException failure = new IllegalStateException("Simulated delivery storage failure");
        doThrow(failure).when(deliveries).saveAll(anyList());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.ingest("order.confirmed",
                        JSON.readTree("{\"orderNumber\":\"128\"}"), "rollback-request"));

        assertSame(failure, thrown);
        verify(deliveries).saveAll(anyList());
        assertTrue(events.findByIdempotencyKey("rollback-request").isEmpty());
        assertEquals(0L, events.count());
        assertEquals(0L, deliveries.count());
        // Setup was committed separately and survives the failed ingestion transaction.
        assertEquals(1L, subscriptions.count());
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
        Event accepted = service.ingest("order.confirmed", payload, "order-128-confirmed");

        assertNotNull(accepted);
        assertNotNull(accepted.getId());
        assertEquals(1L, events.count());
        Event persisted = events.findById(accepted.getId()).orElseThrow();
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
    }

    private WebhookEndpoint saveEndpoint(String name, boolean enabled) {
        Instant createdAt = ACCEPTED_AT.minusSeconds(60);
        WebhookEndpoint endpoint = new WebhookEndpoint(
                name, "https://example.com/webhooks", createdAt);
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
