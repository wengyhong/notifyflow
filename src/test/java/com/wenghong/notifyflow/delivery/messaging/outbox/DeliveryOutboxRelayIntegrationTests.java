package com.wenghong.notifyflow.delivery.messaging.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;
import com.wenghong.notifyflow.delivery.messaging.DeliveryJobPublisher;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.event.domain.Event;
import com.wenghong.notifyflow.event.persistence.EventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, DeliveryOutboxRelay.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeliveryOutboxRelayIntegrationTests {

    @Autowired private TenantRepository tenants;
    private Tenant tenant;

    @BeforeEach
    void createFixtureTenant() {
        tenant = tenants.saveAndFlush(new Tenant("Fixture workspace", Instant.parse("2026-11-20T00:00:00Z")));
    }

    private static final String TEST_SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static final Instant CREATED_AT = Instant.parse("2026-11-10T00:00:00Z");
    private static final Instant PUBLISHED_AT = CREATED_AT.plusSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private DeliveryOutboxRelay relay;
    @Autowired private DeliveryOutboxRepository outbox;
    @Autowired private DeliveryRepository deliveries;
    @Autowired private EventRepository events;
    @Autowired private WebhookEndpointRepository endpoints;

    @MockitoBean private DeliveryJobPublisher publisher;
    @MockitoBean private Clock clock;

    @BeforeEach
    void fixPublicationTime() {
        when(clock.instant()).thenReturn(PUBLISHED_AT);
    }

    @AfterEach
    void cleanUpCommittedRows() {
        // Fixtures and relay calls commit into this test's isolated PostgreSQL container.
        outbox.deleteAllInBatch();
        deliveries.deleteAllInBatch();
        events.deleteAllInBatch();
        endpoints.deleteAllInBatch();
        tenants.deleteAllInBatch();
    }

    @Test
    void emptyOutboxPublishesNothing() {
        assertFalse(relay.publishNext());

        verifyNoInteractions(publisher);
    }

    @Test
    void successfulPublicationCommitsTimestampOnlyAfterPublisherReturns() {
        DeliveryOutboxMessage message = saveMessage(CREATED_AT);
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertNull(outbox.findById(message.getId()).orElseThrow().getPublishedAt());
            return null;
        }).when(publisher).publish(message.getDeliveryId());

        assertTrue(relay.publishNext());

        DeliveryOutboxMessage stored = outbox.findById(message.getId()).orElseThrow();
        assertEquals(PUBLISHED_AT, stored.getPublishedAt());
        assertEquals(CREATED_AT, stored.getCreatedAt());
        assertEquals(message.getDeliveryId(), stored.getDeliveryId());
        assertEquals(DeliveryStatus.PENDING,
                deliveries.findById(message.getDeliveryId()).orElseThrow().getStatus());
        assertEquals(1L, outbox.count());
        assertFalse(relay.publishNext());
        verify(publisher).publish(message.getDeliveryId());
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void publishingFailureLeavesRowPendingAndLaterRunCanRecover() {
        DeliveryOutboxMessage message = saveMessage(CREATED_AT);
        AmqpException failure = new AmqpException("Broker unavailable");
        doThrow(failure).doNothing().when(publisher).publish(message.getDeliveryId());

        assertSame(failure, assertThrows(AmqpException.class, relay::publishNext));

        assertNull(outbox.findById(message.getId()).orElseThrow().getPublishedAt());
        assertEquals(1L, outbox.count());

        assertTrue(relay.publishNext());
        assertEquals(PUBLISHED_AT, outbox.findById(message.getId()).orElseThrow().getPublishedAt());
        verify(publisher, times(2)).publish(message.getDeliveryId());
    }

    @Test
    void commitFailureAfterPublicationLeavesRowPendingAndAllowsDuplicatePublication() {
        DeliveryOutboxMessage message = saveMessage(CREATED_AT);
        IllegalStateException failure = new IllegalStateException("Simulated commit failure");
        doAnswer(invocation -> {
            // Simulate broker acceptance followed by failure while committing the published marker.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw failure;
                }
            });
            return null;
        }).doNothing().when(publisher).publish(message.getDeliveryId());

        assertSame(failure, assertThrows(IllegalStateException.class, relay::publishNext));
        assertNull(outbox.findById(message.getId()).orElseThrow().getPublishedAt());

        assertTrue(relay.publishNext());

        assertEquals(PUBLISHED_AT, outbox.findById(message.getId()).orElseThrow().getPublishedAt());
        assertEquals(1L, outbox.count());
        // This is why the consumer must tolerate repeated delivery IDs.
        verify(publisher, times(2)).publish(message.getDeliveryId());
    }

    @Test
    void eachCallPublishesOneRequestEvenWhenMultipleRowsReferenceSameDelivery() {
        DeliveryOutboxMessage first = saveMessage(CREATED_AT);
        DeliveryOutboxMessage second = outbox.saveAndFlush(
                new DeliveryOutboxMessage(first.getDeliveryId(), CREATED_AT.plusSeconds(1)));

        assertTrue(relay.publishNext());
        assertEquals(PUBLISHED_AT, outbox.findById(first.getId()).orElseThrow().getPublishedAt());
        assertNull(outbox.findById(second.getId()).orElseThrow().getPublishedAt());

        assertTrue(relay.publishNext());
        assertEquals(PUBLISHED_AT, outbox.findById(second.getId()).orElseThrow().getPublishedAt());
        assertFalse(relay.publishNext());
        verify(publisher, times(2)).publish(first.getDeliveryId());
        assertEquals(2L, outbox.count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentRelaySkipsInFlightPublication(boolean anotherMessageExists) throws Exception {
        DeliveryOutboxMessage first = saveMessage(CREATED_AT);
        DeliveryOutboxMessage second = anotherMessageExists ? saveMessage(CREATED_AT.plusSeconds(1)) : null;
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishing.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS), "First publisher was not released");
            return null;
        }).when(publisher).publish(first.getDeliveryId());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstRun = executor.submit(relay::publishNext);
            assertTrue(publishing.await(10, TimeUnit.SECONDS), "First relay did not reach publishing");

            var secondRun = executor.submit(relay::publishNext);
            assertEquals(anotherMessageExists, secondRun.get(5, TimeUnit.SECONDS));
            assertNull(outbox.findById(first.getId()).orElseThrow().getPublishedAt());
            if (second != null) {
                assertEquals(PUBLISHED_AT, outbox.findById(second.getId()).orElseThrow().getPublishedAt());
            }

            release.countDown();
            assertTrue(firstRun.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Relays must finish before cleanup");
        }

        assertEquals(PUBLISHED_AT, outbox.findById(first.getId()).orElseThrow().getPublishedAt());
        verify(publisher).publish(first.getDeliveryId());
        if (second != null) {
            verify(publisher).publish(second.getDeliveryId());
        }
        verifyNoMoreInteractions(publisher);
        assertFalse(relay.publishNext());
    }

    private DeliveryOutboxMessage saveMessage(Instant createdAt) {
        Event event = events.saveAndFlush(new Event(tenant, "order.confirmed",
                JSON.readTree("{\"orderNumber\":\"128\"}"), UUID.randomUUID().toString(), CREATED_AT));
        WebhookEndpoint endpoint = endpoints.saveAndFlush(
                new WebhookEndpoint(tenant, "Warehouse", "https://example.com/webhooks", CREATED_AT, TEST_SIGNING_SECRET));
        Delivery delivery = deliveries.saveAndFlush(new Delivery(event, endpoint, CREATED_AT));
        return outbox.saveAndFlush(new DeliveryOutboxMessage(delivery.getId(), createdAt));
    }
}
