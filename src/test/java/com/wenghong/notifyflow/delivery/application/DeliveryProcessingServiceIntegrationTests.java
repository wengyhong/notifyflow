package com.wenghong.notifyflow.delivery.application;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryAttempt;
import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;
import com.wenghong.notifyflow.delivery.http.WebhookHttpSender;
import com.wenghong.notifyflow.delivery.observability.DeliveryAttemptOutcome;
import com.wenghong.notifyflow.delivery.observability.DeliveryMetrics;
import com.wenghong.notifyflow.delivery.persistence.DeliveryAttemptRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, DeliveryProcessingService.class,
        DeliveryRetryPolicy.class,
        DeliveryProcessingServiceIntegrationTests.FixedClockConfiguration.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class DeliveryProcessingServiceIntegrationTests {

    @Autowired private TenantRepository tenants;
    private Tenant tenant;

    @BeforeEach
    void createFixtureTenant() {
        tenant = tenants.saveAndFlush(new Tenant("Fixture workspace", Instant.parse("2026-11-20T00:00:00Z")));
    }

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Autowired
    private DeliveryProcessingService service;

    @MockitoBean
    private WebhookHttpSender sender;

    @MockitoBean
    private RandomGenerator random;

    @MockitoBean
    private DeliveryRateLimiter rateLimiter;

    @MockitoBean
    private DeliveryMetrics metrics;

    @MockitoSpyBean
    private Clock clock;

    @Autowired
    private DeliveryRepository deliveries;

    @MockitoSpyBean
    private DeliveryAttemptRepository attempts;

    @Autowired
    private EventRepository events;

    @Autowired
    private WebhookEndpointRepository endpoints;

    @BeforeEach
    void allowDeliveriesByDefault() {
        when(rateLimiter.tryAcquire(any(UUID.class)))
                .thenReturn(new DeliveryRateLimitDecision(true, Duration.ZERO));
    }

    @AfterEach
    void cleanUpCommittedRows() {
        // These rows belong to this test's isolated PostgreSQL container.
        attempts.deleteAllInBatch();
        deliveries.deleteAllInBatch();
        events.deleteAllInBatch();
        endpoints.deleteAllInBatch();
        tenants.deleteAllInBatch();
    }

    @ParameterizedTest
    @CsvSource({"199, FAILED", "200, DELIVERED", "204, DELIVERED", "299, DELIVERED",
            "300, FAILED", "400, FAILED", "503, RETRY_SCHEDULED", "599, RETRY_SCHEDULED"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void processSendsOutsideTransactionAndPersistsResponse(
            int responseStatus, DeliveryStatus expectedStatus) throws Exception {
        UUID deliveryId = savePendingDelivery().getId();
        when(sender.send(any(DeliveryWork.class))).thenAnswer(invocation -> {
            DeliveryWork work = invocation.getArgument(0);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(deliveryId, work.id());
            assertEquals("https://example.com/webhooks", work.url());
            assertEquals(SIGNING_SECRET, work.signingSecret());
            assertEquals(JSON.readTree("{\"orderNumber\":\"128\"}"), work.payload());
            assertEquals(DeliveryStatus.PROCESSING,
                    deliveries.findById(deliveryId).orElseThrow().getStatus());
            assertUnfinishedAttempt(work);
            return responseStatus;
        });

        service.process(deliveryId);

        verify(sender).send(any(DeliveryWork.class));
        assertProcessOutcome(deliveryId, expectedStatus, responseStatus, null);
        assertSingleOutcome(responseStatus >= 200 && responseStatus < 300
                ? DeliveryAttemptOutcome.SUCCESS : DeliveryAttemptOutcome.HTTP_FAILURE);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void dueRetryCreatesSecondAttempt() {
        Delivery delivery = savePendingDelivery();
        assertTrue(delivery.tryStartProcessing());

        DeliveryAttempt firstAttempt = new DeliveryAttempt(delivery, 1, NOW);
        firstAttempt.completeWithResponse(503, NOW);
        firstAttempt = attempts.saveAndFlush(firstAttempt);

        delivery.scheduleRetry(NOW);
        deliveries.saveAndFlush(delivery);

        DeliveryWork work = service.claim(delivery.getId()).orElseThrow();

        DeliveryAttempt secondAttempt = attempts.findById(work.attemptId()).orElseThrow();
        assertEquals(2, secondAttempt.getAttemptNumber());
        assertEquals(delivery.getId(), secondAttempt.getDelivery().getId());
        assertEquals(NOW, secondAttempt.getStartedAt());
        assertNull(secondAttempt.getCompletedAt());
        assertNull(secondAttempt.getResponseStatus());
        assertNull(secondAttempt.getErrorMessage());
        assertEquals(2L, attempts.count());

        Delivery persisted = deliveries.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.PROCESSING, persisted.getStatus());
        assertNull(persisted.getNextAttemptAt());

        DeliveryAttempt previous = attempts.findById(firstAttempt.getId()).orElseThrow();
        assertEquals(1, previous.getAttemptNumber());
        assertEquals(Integer.valueOf(503), previous.getResponseStatus());
        assertEquals(NOW, previous.getCompletedAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void processRecordsIOException() throws Exception {
        UUID deliveryId = savePendingDelivery().getId();
        when(sender.send(any(DeliveryWork.class)))
                .thenThrow(new IOException("Connection timed out"));

        service.process(deliveryId);

        verify(sender).send(any(DeliveryWork.class));
        assertProcessOutcome(deliveryId, DeliveryStatus.RETRY_SCHEDULED, null, "Connection timed out");
        assertSingleOutcome(DeliveryAttemptOutcome.IO_FAILURE);
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
        assertProcessOutcome(deliveryId, DeliveryStatus.RETRY_SCHEDULED, null, "IOException");
        assertSingleOutcome(DeliveryAttemptOutcome.IO_FAILURE);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void processSkipsMissingDelivery() throws Exception {
        service.process(UUID.randomUUID());

        verifyNoInteractions(sender, metrics);
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

        verifyNoInteractions(sender, metrics);
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
        assertSingleOutcome(DeliveryAttemptOutcome.INTERRUPTED);
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
        assertSingleOutcome(DeliveryAttemptOutcome.SUCCESS);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void ioFailureStillCountsOnceWhenOutcomePersistenceFails() throws Exception {
        UUID deliveryId = savePendingDelivery().getId();
        DataAccessResourceFailureException storageFailure =
                new DataAccessResourceFailureException("Simulated outcome storage failure");
        when(sender.send(any(DeliveryWork.class))).thenAnswer(invocation -> {
            DeliveryWork work = invocation.getArgument(0);
            doThrow(storageFailure).when(attempts).findById(work.attemptId());
            throw new IOException("Connection timed out");
        });

        assertSame(storageFailure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.process(deliveryId)));
        assertSingleOutcome(DeliveryAttemptOutcome.IO_FAILURE);
        assertEquals(DeliveryStatus.PROCESSING, deliveries.findById(deliveryId).orElseThrow().getStatus());
        assertNull(attempts.findAll().getFirst().getCompletedAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void retryThenSuccessCountsTwoSendsAndDuplicateDeliveredJobCountsNothing() throws Exception {
        UUID deliveryId = savePendingDelivery().getId();
        when(sender.send(any(DeliveryWork.class))).thenReturn(503, 204);

        service.process(deliveryId);
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        service.process(deliveryId);
        service.process(deliveryId);

        verify(metrics).recordAttempt(eq(DeliveryAttemptOutcome.HTTP_FAILURE),
                argThat(elapsed -> elapsed != null && !elapsed.isNegative()));
        verify(metrics).recordAttempt(eq(DeliveryAttemptOutcome.SUCCESS),
                argThat(elapsed -> elapsed != null && !elapsed.isNegative()));
        verifyNoMoreInteractions(metrics);
        verify(sender, times(2)).send(any(DeliveryWork.class));
        assertEquals(DeliveryStatus.DELIVERED, deliveries.findById(deliveryId).orElseThrow().getStatus());
        assertEquals(2, attempts.count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void recordFailurePersistsErrorWithoutHttpResponseStatus() {
        DeliveryWork work = service.claim(savePendingDelivery().getId()).orElseThrow();

        service.recordFailure(work.id(), work.attemptId(), "Connection timed out");

        Delivery delivery = deliveries.findById(work.id()).orElseThrow();
        DeliveryAttempt attempt = attempts.findById(work.attemptId()).orElseThrow();
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, delivery.getStatus());
        assertEquals(NOW.plusSeconds(5), delivery.getNextAttemptAt());
        assertEquals(NOW, delivery.getCreatedAt());
        assertEquals(work.id(), attempt.getDelivery().getId());
        assertEquals(1, attempt.getAttemptNumber());
        assertEquals("Connection timed out", attempt.getErrorMessage());
        assertEquals(NOW, attempt.getStartedAt());
        assertEquals(NOW, attempt.getCompletedAt());
        assertNull(attempt.getResponseStatus());
        assertEquals(1L, deliveries.count());
        assertEquals(1L, attempts.count());
        verifyNoInteractions(metrics);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void claimReturnsEmptyForMissingDelivery() {
        assertTrue(service.claim(UUID.randomUUID()).isEmpty());
        verifyNoInteractions(rateLimiter);
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
            "408, RETRY_SCHEDULED",
            "429, RETRY_SCHEDULED",
            "500, RETRY_SCHEDULED",
            "503, RETRY_SCHEDULED",
            "599, RETRY_SCHEDULED"
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
        assertEquals(expectedStatus == DeliveryStatus.RETRY_SCHEDULED ? NOW.plusSeconds(5) : null,
                delivery.getNextAttemptAt());
        assertEquals(NOW, delivery.getCreatedAt());
        assertEquals(work.id(), attempt.getDelivery().getId());
        assertEquals(1, attempt.getAttemptNumber());
        assertEquals(Integer.valueOf(responseStatus), attempt.getResponseStatus());
        assertEquals(NOW, attempt.getStartedAt());
        assertEquals(NOW, attempt.getCompletedAt());
        assertNull(attempt.getErrorMessage());
        assertEquals(1L, deliveries.count());
        assertEquals(1L, attempts.count());
        verifyNoInteractions(metrics);
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
        verifyNoInteractions(rateLimiter);
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"PENDING", "RETRY_SCHEDULED"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentClaimsReturnWorkToOnlyOneWorker(DeliveryStatus initialStatus) throws Exception {
        UUID deliveryId = savePendingDelivery().getId();
        int expectedAttemptNumber = 1;
        if (initialStatus == DeliveryStatus.RETRY_SCHEDULED) {
            DeliveryWork first = service.claim(deliveryId).orElseThrow();
            DeliveryAttempt previous = attempts.findById(first.attemptId()).orElseThrow();
            previous.completeWithResponse(503, NOW);
            attempts.saveAndFlush(previous);
            Delivery delivery = deliveries.findById(deliveryId).orElseThrow();
            delivery.scheduleRetry(NOW);
            deliveries.saveAndFlush(delivery);
            expectedAttemptNumber = 2;
        }
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
        assertEquals(SIGNING_SECRET, claimed.getFirst().signingSecret());
        assertEquals(JSON.readTree("{\"orderNumber\":\"128\"}"), claimed.getFirst().payload());
        assertEquals(1L, deliveries.count());
        assertEquals(DeliveryStatus.PROCESSING,
                deliveries.findById(deliveryId).orElseThrow().getStatus());
        assertEquals((long) expectedAttemptNumber, attempts.count());
        assertNull(deliveries.findById(deliveryId).orElseThrow().getNextAttemptAt());
        assertUnfinishedAttempt(claimed.getFirst(), expectedAttemptNumber);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void claimCommitsProcessingStateAndReturnsDeliveryWork() {
        JsonNode payload = JSON.readTree("""
                {"orderNumber":"128","items":[{"sku":"A","quantity":2}]}
                """);
        Event event = events.saveAndFlush(new Event(tenant,
                "order.confirmed", payload, "claim-request", NOW));
        WebhookEndpoint endpoint = endpoints.saveAndFlush(new WebhookEndpoint(tenant,
                "Warehouse", "https://example.com/webhooks", NOW, SIGNING_SECRET));
        UUID deliveryId = deliveries.saveAndFlush(new Delivery(event, endpoint, NOW)).getId();

        // No test transaction: claim must acquire the lock and commit independently.
        DeliveryWork work = service.claim(deliveryId).orElseThrow();

        assertEquals(deliveryId, work.id());
        assertEquals("https://example.com/webhooks", work.url());
        assertEquals(payload, work.payload());
        assertEquals(SIGNING_SECRET, work.signingSecret());
        assertNull(work.previousSigningSecret());
        assertNull(work.previousSigningSecretExpiresAt());
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

    @ParameterizedTest
    @ValueSource(longs = {-1, 3600})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void claimCopiesBothSecretsAndExpiryWithoutDecidingSendTimeValidity(long secondsUntilExpiry) {
        String nextSecret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        Instant expiry = NOW.plusSeconds(secondsUntilExpiry);
        Event event = events.saveAndFlush(new Event(tenant,
                "order.confirmed", JSON.readTree("{\"orderNumber\":\"128\"}"), "rotation-claim", NOW));
        WebhookEndpoint endpoint = new WebhookEndpoint(tenant,
                "Warehouse", "https://example.com/webhooks", NOW.minusSeconds(10000), SIGNING_SECRET);
        endpoint.rotateSigningSecret(nextSecret, NOW.minusSeconds(7200), expiry);
        endpoint = endpoints.saveAndFlush(endpoint);
        UUID id = deliveries.saveAndFlush(new Delivery(event, endpoint, NOW)).getId();

        DeliveryWork work = service.claim(id).orElseThrow();

        assertEquals(id, work.id());
        assertEquals(nextSecret, work.signingSecret());
        assertEquals(SIGNING_SECRET, work.previousSigningSecret());
        assertEquals(expiry, work.previousSigningSecretExpiresAt());
        assertUnfinishedAttempt(work);
    }

    @ParameterizedTest
    @CsvSource({
            "1, RETRY_SCHEDULED, 5",
            "2, RETRY_SCHEDULED, 10",
            "3, RETRY_SCHEDULED, 20",
            "4, RETRY_SCHEDULED, 40",
            "5, FAILED, 0"
    })
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void retryableResponseUsesBackoffAndStopsAfterFifthAttempt(
            int attemptNumber, DeliveryStatus expectedStatus, long delaySeconds) {
        DeliveryWork work = saveProcessingAttempt(attemptNumber);

        service.recordResponse(work.id(), work.attemptId(), 503);

        assertRecordedAttempt(work, attemptNumber, expectedStatus, 503, null, delaySeconds);
    }

    @ParameterizedTest
    @CsvSource({
            "1, RETRY_SCHEDULED, 5",
            "2, RETRY_SCHEDULED, 10",
            "3, RETRY_SCHEDULED, 20",
            "4, RETRY_SCHEDULED, 40",
            "5, FAILED, 0"
    })
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void networkFailureUsesBackoffAndStopsAfterFifthAttempt(
            int attemptNumber, DeliveryStatus expectedStatus, long delaySeconds) {
        DeliveryWork work = saveProcessingAttempt(attemptNumber);

        service.recordFailure(work.id(), work.attemptId(), "Connection timed out");

        assertRecordedAttempt(work, attemptNumber, expectedStatus, null, "Connection timed out", delaySeconds);
    }

    @ParameterizedTest
    @CsvSource({"204, DELIVERED", "400, FAILED"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void finalAttemptCanSucceedOrFailPermanently(int responseStatus, DeliveryStatus expectedStatus) {
        DeliveryWork work = saveProcessingAttempt(5);

        service.recordResponse(work.id(), work.attemptId(), responseStatus);

        assertRecordedAttempt(work, 5, expectedStatus, responseStatus, null, 0);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void processSkipsRetryBeforeDueTimeWithoutCreatingAnotherAttempt() throws Exception {
        DeliveryWork first = service.claim(savePendingDelivery().getId()).orElseThrow();
        service.recordResponse(first.id(), first.attemptId(), 503);

        service.process(first.id());

        verifyNoInteractions(sender, metrics);
        assertRecordedAttempt(first, 1, DeliveryStatus.RETRY_SCHEDULED, 503, null, 5);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void retryClaimRollsBackScheduleWhenAttemptCreationFails() {
        DeliveryWork first = service.claim(savePendingDelivery().getId()).orElseThrow();
        service.recordResponse(first.id(), first.attemptId(), 503);
        Delivery delivery = deliveries.findById(first.id()).orElseThrow();
        // Make the persisted schedule due without changing the service's fixed clock.
        assertTrue(delivery.tryStartRetry(NOW.plusSeconds(5)));
        delivery.scheduleRetry(NOW);
        deliveries.saveAndFlush(delivery);
        IllegalStateException failure = new IllegalStateException("Simulated retry storage failure");
        doThrow(failure).when(attempts).saveAndFlush(any(DeliveryAttempt.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.claim(first.id()));

        assertSame(failure, thrown);
        Delivery persisted = deliveries.findById(first.id()).orElseThrow();
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, persisted.getStatus());
        assertEquals(NOW, persisted.getNextAttemptAt());
        assertEquals(1L, attempts.count());
        assertEquals(Integer.valueOf(503),
                attempts.findById(first.attemptId()).orElseThrow().getResponseStatus());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void recordedRetryScheduleIncludesNonzeroJitter(boolean networkFailure) {
        DeliveryWork work = saveProcessingAttempt(2);
        when(random.nextLong(2001L)).thenReturn(1500L);

        if (networkFailure) {
            service.recordFailure(work.id(), work.attemptId(), "Connection timed out");
        } else {
            service.recordResponse(work.id(), work.attemptId(), 503);
        }

        Delivery delivery = deliveries.findById(work.id()).orElseThrow();
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, delivery.getStatus());
        assertEquals(NOW.plusMillis(11500), delivery.getNextAttemptAt());
        DeliveryAttempt attempt = attempts.findById(work.attemptId()).orElseThrow();
        assertEquals(NOW, attempt.getCompletedAt());
        assertEquals(networkFailure ? null : Integer.valueOf(503), attempt.getResponseStatus());
        assertEquals(networkFailure ? "Connection timed out" : null, attempt.getErrorMessage());
        assertEquals(2L, attempts.count());
        verify(random).nextLong(2001L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rateLimitedProcessReschedulesWithoutHttpOrAttempt() throws Exception {
        Delivery delivery = savePendingDelivery();
        UUID endpointId = delivery.getEndpoint().getId();
        when(rateLimiter.tryAcquire(endpointId))
                .thenReturn(new DeliveryRateLimitDecision(false, Duration.ofMillis(250)));

        service.process(delivery.getId());

        Delivery stored = deliveries.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, stored.getStatus());
        assertEquals(NOW.plusMillis(250), stored.getNextAttemptAt());
        assertEquals(NOW, stored.getCreatedAt());
        assertEquals(1, stored.getRetryCycleStartAttempt());
        assertEquals(0L, attempts.count());
        verify(rateLimiter).tryAcquire(endpointId);
        verify(attempts, never()).findFirstByDelivery_IdOrderByAttemptNumberDesc(delivery.getId());
        verify(attempts, never()).saveAndFlush(any(DeliveryAttempt.class));
        verifyNoInteractions(sender, metrics);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deferredDeliveryWaitsUntilDueThenCreatesItsFirstAttempt() {
        Delivery delivery = savePendingDelivery();
        UUID endpointId = delivery.getEndpoint().getId();
        when(rateLimiter.tryAcquire(endpointId)).thenReturn(
                new DeliveryRateLimitDecision(false, Duration.ofSeconds(1)),
                new DeliveryRateLimitDecision(true, Duration.ZERO));

        assertTrue(service.claim(delivery.getId()).isEmpty());
        assertTrue(service.claim(delivery.getId()).isEmpty());
        verify(rateLimiter).tryAcquire(endpointId);
        assertEquals(0L, attempts.count());
        assertEquals(NOW.plusSeconds(1),
                deliveries.findById(delivery.getId()).orElseThrow().getNextAttemptAt());

        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        DeliveryWork work = service.claim(delivery.getId()).orElseThrow();

        DeliveryAttempt attempt = attempts.findById(work.attemptId()).orElseThrow();
        assertEquals(1, attempt.getAttemptNumber());
        assertEquals(NOW.plusSeconds(1), attempt.getStartedAt());
        assertNull(attempt.getCompletedAt());
        assertEquals(1L, attempts.count());
        Delivery stored = deliveries.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.PROCESSING, stored.getStatus());
        assertNull(stored.getNextAttemptAt());
        verify(rateLimiter, times(2)).tryAcquire(endpointId);
        verifyNoInteractions(sender);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void repeatedRateLimitDeferralsPreserveAttemptHistoryAndRetryBudget() {
        DeliveryWork fourth = saveProcessingAttempt(4);
        DeliveryAttempt previous = attempts.findById(fourth.attemptId()).orElseThrow();
        previous.completeWithResponse(503, NOW);
        attempts.saveAndFlush(previous);
        Delivery delivery = deliveries.findById(fourth.id()).orElseThrow();
        delivery.scheduleRetry(NOW);
        deliveries.saveAndFlush(delivery);
        UUID endpointId = delivery.getEndpoint().getId();
        when(rateLimiter.tryAcquire(endpointId)).thenReturn(
                new DeliveryRateLimitDecision(false, Duration.ofSeconds(1)),
                new DeliveryRateLimitDecision(false, Duration.ofSeconds(1)),
                new DeliveryRateLimitDecision(false, Duration.ofSeconds(1)),
                new DeliveryRateLimitDecision(true, Duration.ZERO));

        for (int second = 0; second < 3; second++) {
            when(clock.instant()).thenReturn(NOW.plusSeconds(second));
            assertTrue(service.claim(delivery.getId()).isEmpty());
            Delivery stored = deliveries.findById(delivery.getId()).orElseThrow();
            assertEquals(DeliveryStatus.RETRY_SCHEDULED, stored.getStatus());
            assertEquals(NOW.plusSeconds(second + 1), stored.getNextAttemptAt());
            assertEquals(1, stored.getRetryCycleStartAttempt());
            assertEquals(4L, attempts.count());
        }

        when(clock.instant()).thenReturn(NOW.plusSeconds(3));
        DeliveryWork fifth = service.claim(delivery.getId()).orElseThrow();
        assertEquals(5, attempts.findById(fifth.attemptId()).orElseThrow().getAttemptNumber());
        service.recordResponse(fifth.id(), fifth.attemptId(), 503);

        Delivery stored = deliveries.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.FAILED, stored.getStatus());
        assertNull(stored.getNextAttemptAt());
        assertEquals(1, stored.getRetryCycleStartAttempt());
        assertEquals(5L, attempts.count());
        assertEquals(Integer.valueOf(503),
                attempts.findById(fourth.attemptId()).orElseThrow().getResponseStatus());
        verify(rateLimiter, times(4)).tryAcquire(endpointId);
        verifyNoInteractions(sender);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deferralStartsFromClockReadingAfterLimiterReturns() {
        Delivery delivery = savePendingDelivery();
        when(rateLimiter.tryAcquire(delivery.getEndpoint().getId())).thenAnswer(invocation -> {
            // Redis may take time to respond; its retry delay starts after that wait.
            when(clock.instant()).thenReturn(NOW.plusSeconds(2));
            return new DeliveryRateLimitDecision(false, Duration.ofSeconds(1));
        });

        assertTrue(service.claim(delivery.getId()).isEmpty());

        Delivery stored = deliveries.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, stored.getStatus());
        assertEquals(NOW.plusSeconds(3), stored.getNextAttemptAt());
        assertEquals(0L, attempts.count());
        verifyNoInteractions(sender);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void unexpectedLimiterFailureRollsBackClaim() {
        Delivery delivery = savePendingDelivery();
        IllegalStateException failure = new IllegalStateException("Unexpected limiter failure");
        when(rateLimiter.tryAcquire(delivery.getEndpoint().getId())).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.claim(delivery.getId())));

        Delivery stored = deliveries.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.PENDING, stored.getStatus());
        assertNull(stored.getNextAttemptAt());
        assertEquals(0L, attempts.count());
        verifyNoInteractions(sender);
    }

    private void assertSingleOutcome(DeliveryAttemptOutcome outcome) {
        verify(metrics).recordAttempt(eq(outcome),
                argThat(elapsed -> elapsed != null && !elapsed.isNegative()));
        verifyNoMoreInteractions(metrics);
    }

    private DeliveryWork saveProcessingAttempt(int attemptNumber) {
        Delivery delivery = savePendingDelivery();
        assertTrue(delivery.tryStartProcessing());
        delivery = deliveries.saveAndFlush(delivery);
        for (int number = 1; number < attemptNumber; number++) {
            DeliveryAttempt previous = new DeliveryAttempt(delivery, number, NOW);
            previous.completeWithResponse(503, NOW);
            attempts.saveAndFlush(previous);
        }
        DeliveryAttempt current = attempts.saveAndFlush(new DeliveryAttempt(delivery, attemptNumber, NOW));
        return new DeliveryWork(delivery.getId(), current.getId(),
                "https://example.com/webhooks", JSON.readTree("{\"orderNumber\":\"128\"}"), SIGNING_SECRET, null, null);
    }

    private void assertRecordedAttempt(DeliveryWork work, int attemptNumber, DeliveryStatus status,
            Integer responseStatus, String errorMessage, long delaySeconds) {
        Delivery delivery = deliveries.findById(work.id()).orElseThrow();
        assertEquals(status, delivery.getStatus());
        assertEquals(delaySeconds == 0 ? null : NOW.plusSeconds(delaySeconds), delivery.getNextAttemptAt());
        DeliveryAttempt attempt = attempts.findById(work.attemptId()).orElseThrow();
        assertEquals(work.id(), attempt.getDelivery().getId());
        assertEquals(attemptNumber, attempt.getAttemptNumber());
        assertEquals(NOW, attempt.getCompletedAt());
        assertEquals(responseStatus, attempt.getResponseStatus());
        assertEquals(errorMessage, attempt.getErrorMessage());
        assertEquals((long) attemptNumber, attempts.count());
    }

    private void assertProcessOutcome(UUID deliveryId, DeliveryStatus status,
            Integer responseStatus, String errorMessage) {
        Delivery persisted = deliveries.findById(deliveryId).orElseThrow();
        assertEquals(status, persisted.getStatus());
        assertEquals(status == DeliveryStatus.RETRY_SCHEDULED ? NOW.plusSeconds(5) : null,
                persisted.getNextAttemptAt());
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
        assertUnfinishedAttempt(work, 1);
    }

    private void assertUnfinishedAttempt(DeliveryWork work, int expectedAttemptNumber) {
        assertNotNull(work.attemptId());
        DeliveryAttempt attempt = attempts.findById(work.attemptId()).orElseThrow();
        assertEquals(work.id(), attempt.getDelivery().getId());
        assertEquals(expectedAttemptNumber, attempt.getAttemptNumber());
        assertEquals(NOW, attempt.getStartedAt());
        assertNull(attempt.getCompletedAt());
        assertNull(attempt.getResponseStatus());
        assertNull(attempt.getErrorMessage());
    }

    private Delivery savePendingDelivery() {
        Event event = events.saveAndFlush(new Event(tenant,
                "order.confirmed", JSON.readTree("{\"orderNumber\":\"128\"}"), "claim-request", NOW));
        WebhookEndpoint endpoint = endpoints.saveAndFlush(new WebhookEndpoint(tenant,
                "Warehouse", "https://example.com/webhooks", NOW, SIGNING_SECRET));
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
