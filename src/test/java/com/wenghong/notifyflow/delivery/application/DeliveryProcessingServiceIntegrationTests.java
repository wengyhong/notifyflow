package com.wenghong.notifyflow.delivery.application;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryAttempt;
import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;
import com.wenghong.notifyflow.delivery.http.WebhookHttpSender;
import com.wenghong.notifyflow.delivery.persistence.DeliveryAttemptRepository;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.event.domain.Event;
import com.wenghong.notifyflow.event.persistence.EventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, DeliveryProcessingService.class,
        DeliveryProcessingServiceIntegrationTests.FixedClockConfiguration.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class DeliveryProcessingServiceIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private DeliveryProcessingService service;

    @MockitoBean
    private WebhookHttpSender sender;

    @Autowired
    private DeliveryRepository deliveries;

    @MockitoSpyBean
    private DeliveryAttemptRepository attempts;

    @Autowired
    private EventRepository events;

    @Autowired
    private WebhookEndpointRepository endpoints;

    @AfterEach
    void cleanUpCommittedRows() {
        // These rows belong to this test's isolated PostgreSQL container.
        attempts.deleteAllInBatch();
        deliveries.deleteAllInBatch();
        events.deleteAllInBatch();
        endpoints.deleteAllInBatch();
    }

    @ParameterizedTest
    @CsvSource({"204, DELIVERED", "503, FAILED"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void processSendsOutsideTransactionAndPersistsResponse(
            int responseStatus, DeliveryStatus expectedStatus) throws Exception {
        UUID deliveryId = savePendingDelivery().getId();
        when(sender.send(any(DeliveryWork.class))).thenAnswer(invocation -> {
            DeliveryWork work = invocation.getArgument(0);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(deliveryId, work.id());
            assertEquals("https://example.com/webhooks", work.url());
            assertEquals(JSON.readTree("{\"orderNumber\":\"128\"}"), work.payload());
            assertEquals(DeliveryStatus.PROCESSING,
                    deliveries.findById(deliveryId).orElseThrow().getStatus());
            assertUnfinishedAttempt(work);
            return responseStatus;
        });

        service.process(deliveryId);

        verify(sender).send(any(DeliveryWork.class));
        assertProcessOutcome(deliveryId, expectedStatus, responseStatus, null);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void processRecordsIOException() throws Exception {
        UUID deliveryId = savePendingDelivery().getId();
        when(sender.send(any(DeliveryWork.class)))
                .thenThrow(new IOException("Connection timed out"));

        service.process(deliveryId);

        verify(sender).send(any(DeliveryWork.class));
        assertProcessOutcome(deliveryId, DeliveryStatus.FAILED, null, "Connection timed out");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void processUsesExceptionNameWhenIOExceptionHasNoUsefulMessage(String message) throws Exception {
        UUID deliveryId = savePendingDelivery().getId();
        when(sender.send(any(DeliveryWork.class))).thenThrow(new IOException(message));

        service.process(deliveryId);

        verify(sender).send(any(DeliveryWork.class));
        assertProcessOutcome(deliveryId, DeliveryStatus.FAILED, null, "IOException");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void processSkipsMissingDelivery() throws Exception {
        service.process(UUID.randomUUID());

        verifyNoInteractions(sender);
        assertEquals(0L, deliveries.count());
        assertEquals(0L, attempts.count());
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"PROCESSING", "DELIVERED", "FAILED"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void processSkipsNonPendingDelivery(DeliveryStatus status) throws Exception {
        Delivery delivery = savePendingDelivery();
        delivery.tryStartProcessing();
        if (status == DeliveryStatus.DELIVERED) {
            delivery.markDelivered();
        } else if (status == DeliveryStatus.FAILED) {
            delivery.markFailed();
        }
        deliveries.saveAndFlush(delivery);

        service.process(delivery.getId());

        verifyNoInteractions(sender);
        assertEquals(status, deliveries.findById(delivery.getId()).orElseThrow().getStatus());
        assertEquals(0L, attempts.count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void processRestoresInterruptFlagAndRethrowsWithoutCompletingAttempt() throws Exception {
        UUID deliveryId = savePendingDelivery().getId();
        InterruptedException failure = new InterruptedException("Worker interrupted");
        when(sender.send(any(DeliveryWork.class))).thenThrow(failure);

        try {
            InterruptedException thrown = assertThrows(InterruptedException.class,
                    () -> service.process(deliveryId));
            assertSame(failure, thrown);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // Clear the flag before JDBC reads and subsequent tests on this thread.
            Thread.interrupted();
        }

        ArgumentCaptor<DeliveryWork> work = ArgumentCaptor.forClass(DeliveryWork.class);
        verify(sender).send(work.capture());
        assertEquals(DeliveryStatus.PROCESSING,
                deliveries.findById(deliveryId).orElseThrow().getStatus());
        assertEquals(1L, attempts.count());
        assertUnfinishedAttempt(work.getValue());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void processPropagatesResponseStorageFailureWithoutRecordingHttpFailure() throws Exception {
        UUID deliveryId = savePendingDelivery().getId();
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("Simulated outcome storage failure");
        when(sender.send(any(DeliveryWork.class))).thenAnswer(invocation -> {
            DeliveryWork work = invocation.getArgument(0);
            doThrow(failure).when(attempts).findById(work.attemptId());
            return 204;
        });

        DataAccessResourceFailureException thrown = assertThrows(DataAccessResourceFailureException.class,
                () -> service.process(deliveryId));

        assertSame(failure, thrown);
        ArgumentCaptor<DeliveryWork> work = ArgumentCaptor.forClass(DeliveryWork.class);
        verify(sender).send(work.capture());
        // Only the response recording path should try loading the attempt.
        verify(attempts).findById(work.getValue().attemptId());
        assertEquals(DeliveryStatus.PROCESSING,
                deliveries.findById(deliveryId).orElseThrow().getStatus());
        List<DeliveryAttempt> stored = attempts.findAll();
        assertEquals(1, stored.size());
        assertNull(stored.getFirst().getCompletedAt());
        assertNull(stored.getFirst().getResponseStatus());
        assertNull(stored.getFirst().getErrorMessage());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void recordFailurePersistsErrorWithoutHttpResponseStatus() {
        DeliveryWork work = service.claim(savePendingDelivery().getId()).orElseThrow();

        service.recordFailure(work.id(), work.attemptId(), "Connection timed out");

        Delivery delivery = deliveries.findById(work.id()).orElseThrow();
        DeliveryAttempt attempt = attempts.findById(work.attemptId()).orElseThrow();
        assertEquals(DeliveryStatus.FAILED, delivery.getStatus());
        assertEquals(NOW, delivery.getCreatedAt());
        assertEquals(work.id(), attempt.getDelivery().getId());
        assertEquals(1, attempt.getAttemptNumber());
        assertEquals("Connection timed out", attempt.getErrorMessage());
        assertEquals(NOW, attempt.getStartedAt());
        assertEquals(NOW, attempt.getCompletedAt());
        assertNull(attempt.getResponseStatus());
        assertEquals(1L, deliveries.count());
        assertEquals(1L, attempts.count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void claimReturnsEmptyForMissingDelivery() {
        assertTrue(service.claim(UUID.randomUUID()).isEmpty());
        assertEquals(0L, deliveries.count());
        assertEquals(0L, attempts.count());
    }

    @ParameterizedTest
    @CsvSource({
            "200, DELIVERED",
            "204, DELIVERED",
            "299, DELIVERED",
            "300, FAILED",
            "400, FAILED",
            "503, FAILED"
    })
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void recordResponsePersistsAttemptAndDeliveryOutcome(
            int responseStatus, DeliveryStatus expectedStatus) {
        DeliveryWork work = service.claim(savePendingDelivery().getId()).orElseThrow();

        service.recordResponse(work.id(), work.attemptId(), responseStatus);

        // These reads happen after the recording transaction commits.
        Delivery delivery = deliveries.findById(work.id()).orElseThrow();
        DeliveryAttempt attempt = attempts.findById(work.attemptId()).orElseThrow();
        assertEquals(expectedStatus, delivery.getStatus());
        assertEquals(NOW, delivery.getCreatedAt());
        assertEquals(work.id(), attempt.getDelivery().getId());
        assertEquals(1, attempt.getAttemptNumber());
        assertEquals(Integer.valueOf(responseStatus), attempt.getResponseStatus());
        assertEquals(NOW, attempt.getStartedAt());
        assertEquals(NOW, attempt.getCompletedAt());
        assertNull(attempt.getErrorMessage());
        assertEquals(1L, deliveries.count());
        assertEquals(1L, attempts.count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void claimRollsBackProcessingStateWhenAttemptCreationFails() {
        UUID deliveryId = savePendingDelivery().getId();
        IllegalStateException failure = new IllegalStateException("Simulated attempt storage failure");
        doThrow(failure).when(attempts).saveAndFlush(any(DeliveryAttempt.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.claim(deliveryId));

        assertSame(failure, thrown);
        verify(attempts).saveAndFlush(any(DeliveryAttempt.class));
        assertEquals(DeliveryStatus.PENDING,
                deliveries.findById(deliveryId).orElseThrow().getStatus());
        assertEquals(1L, deliveries.count());
        assertEquals(0L, attempts.count());
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"PROCESSING", "DELIVERED", "FAILED"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void claimReturnsEmptyWithoutChangingNonPendingDelivery(DeliveryStatus status) {
        Delivery delivery = savePendingDelivery();
        assertTrue(delivery.tryStartProcessing());
        if (status == DeliveryStatus.DELIVERED) {
            delivery.markDelivered();
        } else if (status == DeliveryStatus.FAILED) {
            delivery.markFailed();
        }
        deliveries.saveAndFlush(delivery);

        assertTrue(service.claim(delivery.getId()).isEmpty());

        Delivery persisted = deliveries.findById(delivery.getId()).orElseThrow();
        assertEquals(status, persisted.getStatus());
        assertEquals(NOW, persisted.getCreatedAt());
        assertEquals(1L, deliveries.count());
        assertEquals(0L, attempts.count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentClaimsReturnWorkToOnlyOneWorker() throws Exception {
        UUID deliveryId = savePendingDelivery().getId();
        CyclicBarrier start = new CyclicBarrier(2);
        Optional<DeliveryWork> firstResult;
        Optional<DeliveryWork> secondResult;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<DeliveryWork>> firstWorker = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return service.claim(deliveryId);
            });
            Future<Optional<DeliveryWork>> secondWorker = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return service.claim(deliveryId);
            });

            firstResult = firstWorker.get(30, TimeUnit.SECONDS);
            secondResult = secondWorker.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Claim workers did not finish before cleanup");
        }

        // Either worker may win; exactly one must receive work.
        List<DeliveryWork> claimed = List.of(firstResult, secondResult).stream()
                .flatMap(Optional::stream)
                .toList();
        assertEquals(1, claimed.size());
        assertEquals(deliveryId, claimed.getFirst().id());
        assertEquals("https://example.com/webhooks", claimed.getFirst().url());
        assertEquals(JSON.readTree("{\"orderNumber\":\"128\"}"), claimed.getFirst().payload());
        assertEquals(1L, deliveries.count());
        assertEquals(DeliveryStatus.PROCESSING,
                deliveries.findById(deliveryId).orElseThrow().getStatus());
        assertEquals(1L, attempts.count());
        assertUnfinishedAttempt(claimed.getFirst());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void claimCommitsProcessingStateAndReturnsDeliveryWork() {
        JsonNode payload = JSON.readTree("""
                {"orderNumber":"128","items":[{"sku":"A","quantity":2}]}
                """);
        Event event = events.saveAndFlush(new Event(
                "order.confirmed", payload, "claim-request", NOW));
        WebhookEndpoint endpoint = endpoints.saveAndFlush(new WebhookEndpoint(
                "Warehouse", "https://example.com/webhooks", NOW));
        UUID deliveryId = deliveries.saveAndFlush(new Delivery(event, endpoint, NOW)).getId();

        // No test transaction: claim must acquire the lock and commit independently.
        DeliveryWork work = service.claim(deliveryId).orElseThrow();

        assertEquals(deliveryId, work.id());
        assertEquals("https://example.com/webhooks", work.url());
        assertEquals(payload, work.payload());
        assertEquals(1L, attempts.count());
        assertUnfinishedAttempt(work);
        Delivery persisted = deliveries.findById(deliveryId).orElseThrow();
        assertEquals(DeliveryStatus.PROCESSING, persisted.getStatus());
        assertEquals(NOW, persisted.getCreatedAt());

        assertTrue(service.claim(deliveryId).isEmpty());
        assertEquals(DeliveryStatus.PROCESSING,
                deliveries.findById(deliveryId).orElseThrow().getStatus());
        assertEquals(1L, deliveries.count());
        assertEquals(1L, attempts.count());
        assertUnfinishedAttempt(work);
    }

    private void assertProcessOutcome(UUID deliveryId, DeliveryStatus status,
            Integer responseStatus, String errorMessage) {
        assertEquals(status, deliveries.findById(deliveryId).orElseThrow().getStatus());
        List<DeliveryAttempt> stored = attempts.findAll();
        assertEquals(1, stored.size());
        DeliveryAttempt attempt = stored.getFirst();
        assertEquals(deliveryId, attempt.getDelivery().getId());
        assertEquals(1, attempt.getAttemptNumber());
        assertEquals(NOW, attempt.getStartedAt());
        assertEquals(NOW, attempt.getCompletedAt());
        assertEquals(responseStatus, attempt.getResponseStatus());
        assertEquals(errorMessage, attempt.getErrorMessage());
    }

    private void assertUnfinishedAttempt(DeliveryWork work) {
        assertNotNull(work.attemptId());
        DeliveryAttempt attempt = attempts.findById(work.attemptId()).orElseThrow();
        assertEquals(work.id(), attempt.getDelivery().getId());
        assertEquals(1, attempt.getAttemptNumber());
        assertEquals(NOW, attempt.getStartedAt());
        assertNull(attempt.getCompletedAt());
        assertNull(attempt.getResponseStatus());
        assertNull(attempt.getErrorMessage());
    }

    private Delivery savePendingDelivery() {
        Event event = events.saveAndFlush(new Event(
                "order.confirmed", JSON.readTree("{\"orderNumber\":\"128\"}"), "claim-request", NOW));
        WebhookEndpoint endpoint = endpoints.saveAndFlush(new WebhookEndpoint(
                "Warehouse", "https://example.com/webhooks", NOW));
        return deliveries.saveAndFlush(new Delivery(event, endpoint, NOW));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
