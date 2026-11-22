package com.wenghong.notifyflow.delivery.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryAttempt;
import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;
import com.wenghong.notifyflow.delivery.http.WebhookHttpSender;
import com.wenghong.notifyflow.delivery.observability.DeliveryAttemptOutcome;
import com.wenghong.notifyflow.delivery.observability.DeliveryMetrics;
import com.wenghong.notifyflow.delivery.messaging.DeliveryJobPublisher;
import com.wenghong.notifyflow.delivery.messaging.DeliveryRequestedEventListener;
import com.wenghong.notifyflow.delivery.messaging.outbox.DeliveryOutboxMessage;
import com.wenghong.notifyflow.delivery.messaging.outbox.DeliveryOutboxRepository;
import com.wenghong.notifyflow.delivery.persistence.DeliveryAttemptRepository;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import com.wenghong.notifyflow.event.domain.Event;
import com.wenghong.notifyflow.event.persistence.EventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, DeliveryReplayService.class,
        DeliveryRequestedEventListener.class, DeliveryProcessingService.class, DeliveryRetryPolicy.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeliveryReplayServiceIntegrationTests {

    private static final String TEST_SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private DeliveryReplayService service;
    @Autowired private DeliveryProcessingService processing;
    @Autowired private DeliveryRepository deliveries;
    @Autowired private DeliveryAttemptRepository attempts;
    @Autowired private EventRepository events;
    @Autowired private WebhookEndpointRepository endpoints;
    @Autowired private TransactionTemplate transactions;

    @MockitoSpyBean private DeliveryOutboxRepository outbox;

    @MockitoBean private DeliveryJobPublisher publisher;
    @MockitoBean private WebhookHttpSender sender;
    @MockitoBean private RandomGenerator random;
    @MockitoBean private Clock clock;
    @MockitoBean private DeliveryRateLimiter rateLimiter;
    @MockitoBean private DeliveryMetrics metrics;

    @Autowired private TenantRepository tenants;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private Tenant tenant;
    private TenantAccess access;

    @BeforeEach
    void fixCurrentTime() {
        tenant = tenants.saveAndFlush(new Tenant("Company A", NOW));
        access = new TenantAccess(tenant.getId(), TenantRole.ADMIN);
        when(clock.instant()).thenReturn(NOW);
        when(rateLimiter.tryAcquire(any(UUID.class)))
                .thenReturn(new DeliveryRateLimitDecision(true, Duration.ZERO));
    }

    @AfterEach
    void cleanUpCommittedRows() {
        // This test uses an isolated PostgreSQL container and commits its own transactions.
        outbox.deleteAllInBatch();
        attempts.deleteAllInBatch();
        deliveries.deleteAllInBatch();
        events.deleteAllInBatch();
        endpoints.deleteAllInBatch();
        tenants.deleteAllInBatch();
    }

    @Test
    void missingDeliveryReturnsEmptyWithoutPublishing() {
        assertTrue(service.replay(access, UUID.randomUUID()).isEmpty());

        verifyNoInteractions(publisher, sender);
        assertEquals(0L, deliveries.count());
        assertEquals(0L, attempts.count());
        assertEquals(0L, outbox.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FOREIGN_BOTH", "FOREIGN_EVENT", "FOREIGN_ENDPOINT"})
    void inaccessibleDeliveryCannotReplayOrCreateOutboxWork(String ownership) {
        Delivery original = saveFailedDelivery(1);
        Tenant other = tenants.saveAndFlush(new Tenant("Company B", NOW));
        if (ownership.equals("FOREIGN_BOTH") || ownership.endsWith("EVENT")) {
            jdbc.update("UPDATE events SET tenant_id = ? WHERE id = ?",
                    other.getId(), original.getEvent().getId());
        }
        if (ownership.equals("FOREIGN_BOTH") || ownership.endsWith("ENDPOINT")) {
            jdbc.update("UPDATE webhook_endpoints SET tenant_id = ? WHERE id = ?",
                    other.getId(), original.getEndpoint().getId());
        }
        List<UUID> history = attempts.findAll().stream().map(DeliveryAttempt::getId).toList();

        assertTrue(service.replay(access, original.getId()).isEmpty());

        Delivery unchanged = deliveries.findById(original.getId()).orElseThrow();
        assertEquals(DeliveryStatus.FAILED, unchanged.getStatus());
        assertEquals(original.getRetryCycleStartAttempt(), unchanged.getRetryCycleStartAttempt());
        assertEquals(original.getNextAttemptAt(), unchanged.getNextAttemptAt());
        assertEquals(history, attempts.findAll().stream().map(DeliveryAttempt::getId).toList());
        assertEquals(0L, outbox.count());
        verifyNoInteractions(publisher, sender);
    }

    @Test
    void viewerCannotReplayOwnedDeliveryOrCreateOutboxWork() {
        Delivery original = saveFailedDelivery(1);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.replay(new TenantAccess(tenant.getId(), TenantRole.VIEWER), original.getId()));
        assertEquals(DeliveryStatus.FAILED, deliveries.findById(original.getId()).orElseThrow().getStatus());
        assertEquals(1L, attempts.count());
        assertEquals(0L, outbox.count());
        verifyNoInteractions(publisher, sender);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void replayPreservesDeliveryAndHistoryAndCreatesOneOutboxRequest(int previousAttempts) {
        Delivery original = saveFailedDelivery(previousAttempts);
        List<UUID> originalAttemptIds = attempts.findAll().stream().map(DeliveryAttempt::getId).toList();

        Delivery replayed = service.replay(access, original.getId()).orElseThrow();

        assertEquals(original.getId(), replayed.getId());
        assertEquals(DeliveryStatus.PENDING, replayed.getStatus());
        assertEquals(previousAttempts + 1, replayed.getRetryCycleStartAttempt());
        Delivery persisted = deliveries.findById(original.getId()).orElseThrow();
        assertEquals(DeliveryStatus.PENDING, persisted.getStatus());
        assertEquals(previousAttempts + 1, persisted.getRetryCycleStartAttempt());
        assertEquals(original.getEvent().getId(), persisted.getEvent().getId());
        assertEquals(original.getEndpoint().getId(), persisted.getEndpoint().getId());
        assertEquals(NOW, persisted.getCreatedAt());
        assertNull(persisted.getNextAttemptAt());
        assertEquals(1L, deliveries.count());
        assertEquals(1L, events.count());
        assertEquals((long) previousAttempts, attempts.count());
        for (UUID id : originalAttemptIds) {
            DeliveryAttempt previous = attempts.findById(id).orElseThrow();
            assertEquals(NOW, previous.getCompletedAt());
            assertEquals(Integer.valueOf(previousAttempts == 1 ? 400 : 503), previous.getResponseStatus());
        }
        assertPendingOutboxRequests(original.getId(), 1);
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "FAILED")
    void nonFailedDeliveryCannotBeReplayed(DeliveryStatus status) {
        Delivery delivery = saveDelivery(status);

        assertThrows(DeliveryReplayNotAllowedException.class, () -> service.replay(access, delivery.getId()));

        Delivery persisted = deliveries.findById(delivery.getId()).orElseThrow();
        assertEquals(status, persisted.getStatus());
        assertEquals(1, persisted.getRetryCycleStartAttempt());
        assertEquals(status == DeliveryStatus.RETRY_SCHEDULED ? NOW.plusSeconds(60) : null,
                persisted.getNextAttemptAt());
        assertEquals(0L, attempts.count());
        assertEquals(0L, outbox.count());
        verifyNoInteractions(publisher, sender);
    }

    @Test
    void failedDeliveryWithoutHistoryRemainsFailedAndPublishesNothing() {
        Delivery delivery = saveDelivery(DeliveryStatus.FAILED);

        assertThrows(IllegalStateException.class, () -> service.replay(access, delivery.getId()));

        Delivery persisted = deliveries.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.FAILED, persisted.getStatus());
        assertEquals(1, persisted.getRetryCycleStartAttempt());
        assertEquals(0L, attempts.count());
        assertEquals(0L, outbox.count());
        verifyNoInteractions(publisher, sender);
    }

    @Test
    void replayStateAndOutboxRequestCommitTogetherWithoutPublishingToRabbitMq() {
        UUID id = saveFailedDelivery(5).getId();

        transactions.executeWithoutResult(status -> {
            Delivery replayed = service.replay(access, id).orElseThrow();
            assertEquals(DeliveryStatus.PENDING, replayed.getStatus());
            assertEquals(0L, outbox.count());
            verifyNoInteractions(publisher);
        });

        assertEquals(DeliveryStatus.PENDING, deliveries.findById(id).orElseThrow().getStatus());
        assertPendingOutboxRequests(id, 1);
    }

    @Test
    void rolledBackReplayPreservesFailedStateAndDoesNotPublish() {
        UUID id = saveFailedDelivery(5).getId();

        transactions.executeWithoutResult(status -> {
            assertEquals(6, service.replay(access, id).orElseThrow().getRetryCycleStartAttempt());
            verifyNoInteractions(publisher);
            status.setRollbackOnly();
        });

        Delivery persisted = deliveries.findById(id).orElseThrow();
        assertEquals(DeliveryStatus.FAILED, persisted.getStatus());
        assertEquals(1, persisted.getRetryCycleStartAttempt());
        assertNull(persisted.getNextAttemptAt());
        assertEquals(5L, attempts.count());
        assertEquals(0L, outbox.count());
        verifyNoInteractions(publisher, sender);
    }

    @Test
    void repeatedReplayRequestDoesNotCreateAnotherOutboxRequest() {
        UUID id = saveFailedDelivery(5).getId();
        service.replay(access, id).orElseThrow();

        assertThrows(DeliveryReplayNotAllowedException.class, () -> service.replay(access, id));

        assertEquals(6, deliveries.findById(id).orElseThrow().getRetryCycleStartAttempt());
        assertEquals(5L, attempts.count());
        assertPendingOutboxRequests(id, 1);
    }

    @Test
    void concurrentReplayRequestsAcceptOnlyOneAndCreateOneOutboxRequest() throws Exception {
        UUID id = saveFailedDelivery(5).getId();
        CyclicBarrier start = new CyclicBarrier(2);
        List<Delivery> accepted = new ArrayList<>();
        List<Throwable> rejected = new ArrayList<>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return service.replay(access, id);
            });
            var second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return service.replay(access, id);
            });
            for (var future : List.of(first, second)) {
                try {
                    Optional<Delivery> result = future.get(30, TimeUnit.SECONDS);
                    accepted.add(result.orElseThrow());
                } catch (ExecutionException error) {
                    rejected.add(error.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, accepted.size());
        assertEquals(1, rejected.size());
        assertInstanceOf(DeliveryReplayNotAllowedException.class, rejected.getFirst());
        Delivery persisted = deliveries.findById(id).orElseThrow();
        assertEquals(DeliveryStatus.PENDING, persisted.getStatus());
        assertEquals(6, persisted.getRetryCycleStartAttempt());
        assertEquals(5L, attempts.count());
        assertPendingOutboxRequests(id, 1);
    }

    @Test
    void replayedDeliveryCanSucceedWithoutSendingTwiceForDuplicateJob() throws Exception {
        UUID id = saveFailedDelivery(5).getId();
        when(sender.send(any(DeliveryWork.class))).thenReturn(204);
        service.replay(access, id).orElseThrow();

        // Simulate consuming the queued delivery ID; outbox dispatch is tested separately.
        processing.process(id);
        processing.process(id);

        ArgumentCaptor<DeliveryWork> sent = ArgumentCaptor.forClass(DeliveryWork.class);
        verify(sender).send(sent.capture());
        verifyNoMoreInteractions(sender);
        DeliveryAttempt sixth = attempts.findById(sent.getValue().attemptId()).orElseThrow();
        assertEquals(6, sixth.getAttemptNumber());
        assertEquals(Integer.valueOf(204), sixth.getResponseStatus());
        assertEquals(DeliveryStatus.DELIVERED, deliveries.findById(id).orElseThrow().getStatus());
        assertEquals(6L, attempts.count());
        verify(metrics).recordAttempt(eq(DeliveryAttemptOutcome.SUCCESS),
                argThat(elapsed -> elapsed != null && !elapsed.isNegative()));
        verifyNoMoreInteractions(metrics);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replayGetsFiveFreshAttemptsAndCanBeReplayedAgain(boolean networkFailure) {
        UUID id = saveFailedDelivery(5).getId();
        service.replay(access, id).orElseThrow();
        Instant currentTime = NOW;
        long[] delays = {5, 10, 20, 40};

        for (int cycleNumber = 1; cycleNumber <= 5; cycleNumber++) {
            when(clock.instant()).thenReturn(currentTime);
            DeliveryWork work = processing.claim(id).orElseThrow();
            DeliveryAttempt attempt = attempts.findById(work.attemptId()).orElseThrow();
            assertEquals(5 + cycleNumber, attempt.getAttemptNumber());
            if (networkFailure) {
                processing.recordFailure(id, work.attemptId(), "Connection timed out");
            } else {
                processing.recordResponse(id, work.attemptId(), 503);
            }

            Delivery stored = deliveries.findById(id).orElseThrow();
            assertEquals(6, stored.getRetryCycleStartAttempt());
            DeliveryAttempt completed = attempts.findById(work.attemptId()).orElseThrow();
            assertEquals(currentTime, completed.getCompletedAt());
            assertEquals(networkFailure ? null : Integer.valueOf(503), completed.getResponseStatus());
            assertEquals(networkFailure ? "Connection timed out" : null, completed.getErrorMessage());
            if (cycleNumber < 5) {
                currentTime = currentTime.plusSeconds(delays[cycleNumber - 1]);
                assertEquals(DeliveryStatus.RETRY_SCHEDULED, stored.getStatus());
                assertEquals(currentTime, stored.getNextAttemptAt());
            } else {
                assertEquals(DeliveryStatus.FAILED, stored.getStatus());
                assertNull(stored.getNextAttemptAt());
            }
        }

        assertEquals(10L, attempts.count());
        assertTrue(processing.claim(id).isEmpty());
        Delivery replayedAgain = service.replay(access, id).orElseThrow();
        assertEquals(11, replayedAgain.getRetryCycleStartAttempt());
        DeliveryWork eleventh = processing.claim(id).orElseThrow();
        assertEquals(11, attempts.findById(eleventh.attemptId()).orElseThrow().getAttemptNumber());
        processing.recordResponse(id, eleventh.attemptId(), 204);
        assertEquals(DeliveryStatus.DELIVERED, deliveries.findById(id).orElseThrow().getStatus());
        assertEquals(11L, attempts.count());
        assertPendingOutboxRequests(id, 2);
    }

    @Test
    void rateLimitedReplayPreservesItsNewCycleAndResumesAtSixthLifetimeAttempt() throws Exception {
        Delivery original = saveFailedDelivery(5);
        UUID id = original.getId();
        service.replay(access, id).orElseThrow();
        when(rateLimiter.tryAcquire(original.getEndpoint().getId())).thenReturn(
                new DeliveryRateLimitDecision(false, Duration.ofSeconds(1)),
                new DeliveryRateLimitDecision(true, Duration.ZERO));

        processing.process(id);

        Delivery deferred = deliveries.findById(id).orElseThrow();
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, deferred.getStatus());
        assertEquals(NOW.plusSeconds(1), deferred.getNextAttemptAt());
        assertEquals(6, deferred.getRetryCycleStartAttempt());
        assertEquals(5L, attempts.count());
        verifyNoInteractions(sender, metrics);

        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        DeliveryWork work = processing.claim(id).orElseThrow();
        assertEquals(6, attempts.findById(work.attemptId()).orElseThrow().getAttemptNumber());
        processing.recordResponse(id, work.attemptId(), 503);

        Delivery stored = deliveries.findById(id).orElseThrow();
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, stored.getStatus());
        assertEquals(NOW.plusSeconds(6), stored.getNextAttemptAt());
        assertEquals(6, stored.getRetryCycleStartAttempt());
        assertEquals(6L, attempts.count());
        assertPendingOutboxRequests(id, 1);
    }

    @Test
    void outboxStorageFailureRollsBackReplayStateAndCycleStart() {
        UUID id = saveFailedDelivery(5).getId();
        IllegalStateException failure = new IllegalStateException("Simulated outbox storage failure");
        doThrow(failure).when(outbox).save(any(DeliveryOutboxMessage.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> service.replay(access, id));

        assertSame(failure, thrown);
        Delivery persisted = deliveries.findById(id).orElseThrow();
        assertEquals(DeliveryStatus.FAILED, persisted.getStatus());
        assertEquals(1, persisted.getRetryCycleStartAttempt());
        assertEquals(5L, attempts.count());
        assertEquals(0L, outbox.count());
        verifyNoInteractions(publisher, sender);
    }

    private void assertPendingOutboxRequests(UUID deliveryId, int expectedCount) {
        List<DeliveryOutboxMessage> messages = outbox.findAll();
        assertEquals(expectedCount, messages.size());
        for (DeliveryOutboxMessage message : messages) {
            assertNotNull(message.getId());
            assertEquals(deliveryId, message.getDeliveryId());
            assertNotNull(message.getCreatedAt());
            assertNull(message.getPublishedAt());
        }
        verifyNoInteractions(publisher);
    }

    private Delivery saveFailedDelivery(int count) {
        Delivery delivery = saveDelivery(DeliveryStatus.FAILED);
        for (int number = 1; number <= count; number++) {
            DeliveryAttempt attempt = new DeliveryAttempt(delivery, number, NOW);
            attempt.completeWithResponse(count == 1 ? 400 : 503, NOW);
            attempts.saveAndFlush(attempt);
        }
        return delivery;
    }

    private Delivery saveDelivery(DeliveryStatus status) {
        Event event = events.saveAndFlush(new Event(tenant, "order.confirmed",
                JSON.readTree("{\"orderNumber\":\"128\"}"), UUID.randomUUID().toString(), NOW));
        WebhookEndpoint endpoint = endpoints.saveAndFlush(
                new WebhookEndpoint(tenant, "Warehouse", "https://example.com/webhooks", NOW, TEST_SIGNING_SECRET));
        Delivery delivery = new Delivery(event, endpoint, NOW);
        if (status != DeliveryStatus.PENDING) {
            delivery.tryStartProcessing();
        }
        if (status == DeliveryStatus.FAILED) {
            delivery.markFailed();
        } else if (status == DeliveryStatus.DELIVERED) {
            delivery.markDelivered();
        } else if (status == DeliveryStatus.RETRY_SCHEDULED) {
            delivery.scheduleRetry(NOW.plusSeconds(60));
        }
        return deliveries.saveAndFlush(delivery);
    }
}
