package com.wenghong.notifyflow.delivery.application;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wenghong.notifyflow.delivery.http.WebhookHttpSender;
import com.wenghong.notifyflow.delivery.observability.DeliveryMetrics;
import com.wenghong.notifyflow.delivery.persistence.DeliveryAttemptRepository;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryProcessingLoggingTests {
    private final WebhookHttpSender sender = mock(WebhookHttpSender.class);
    private final DeliveryProcessingService service = spy(new DeliveryProcessingService(
            mock(DeliveryRepository.class), Clock.systemUTC(), mock(TransactionTemplate.class),
            mock(DeliveryAttemptRepository.class), sender, mock(DeliveryRetryPolicy.class),
            mock(DeliveryRateLimiter.class), mock(DeliveryMetrics.class)));
    private final DeliveryWork work = new DeliveryWork(UUID.randomUUID(), UUID.randomUUID(),
            "https://example.com/private-destination",
            JsonMapper.builder().build().readTree("{\"privatePayload\":\"customer-sensitive-data\"}"),
            "current-secret-marker", "previous-secret-marker", Instant.parse("2026-11-22T00:00:00Z"));
    private final Logger logger = (Logger) LoggerFactory.getLogger(DeliveryProcessingService.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        captured.start();
        logger.addAppender(captured);
        doReturn(Optional.of(work)).when(service).claim(work.id());
        doNothing().when(service).recordResponse(any(), any(), anyInt());
        doNothing().when(service).recordFailure(any(), any(), anyString());
    }

    @AfterEach
    void restoreLogger() {
        logger.detachAppender(captured);
        captured.stop();
        logger.setLevel(previousLevel);
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 503})
    void responseLogsIdentifyTheAttemptAndHttpStatus(int status) throws Exception {
        when(sender.send(work)).thenReturn(status);

        service.process(work.id());

        assertEquals(2, captured.list.size());
        assertEvent(0, Level.INFO, "Starting webhook send", Map.of());
        assertEvent(1, Level.INFO, "Webhook HTTP response received", Map.of("responseStatus", status));
        verify(service).recordResponse(work.id(), work.attemptId(), status);
        assertNoSensitiveData();
    }

    @Test
    void ioFailureLogsOnlyExceptionTypeAndAttemptIdentifiers() throws Exception {
        String privateMessage = "Connection to " + work.url() + " failed with " + work.signingSecret();
        when(sender.send(work)).thenThrow(new IOException(privateMessage));

        service.process(work.id());

        assertEquals(2, captured.list.size());
        assertEvent(0, Level.INFO, "Starting webhook send", Map.of());
        assertEvent(1, Level.WARN, "Webhook send failed", Map.of("errorType", "IOException"));
        verify(service).recordFailure(work.id(), work.attemptId(), privateMessage);
        assertNoSensitiveData();
    }

    @Test
    void interruptionLogsAttemptIdentifiersAndPreservesInterruption() throws Exception {
        var failure = new InterruptedException("private-interruption-message");
        when(sender.send(work)).thenThrow(failure);

        try {
            assertSame(failure, assertThrows(InterruptedException.class, () -> service.process(work.id())));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        assertEquals(2, captured.list.size());
        assertEvent(0, Level.INFO, "Starting webhook send", Map.of());
        assertEvent(1, Level.WARN, "Webhook send interrupted", Map.of());
        verify(service, never()).recordResponse(any(), any(), anyInt());
        verify(service, never()).recordFailure(any(), any(), anyString());
        assertNoSensitiveData();
    }

    @Test
    void skippedJobDoesNotLogAStartedSend() throws Exception {
        doReturn(Optional.empty()).when(service).claim(work.id());

        service.process(work.id());

        assertTrue(captured.list.isEmpty());
        verifyNoInteractions(sender);
    }

    @Test
    void receivedResponseIsLoggedEvenIfSavingTheOutcomeFails() throws Exception {
        when(sender.send(work)).thenReturn(204);
        var failure = new IllegalStateException("private-storage-message");
        doThrow(failure).when(service).recordResponse(work.id(), work.attemptId(), 204);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.process(work.id())));

        assertEquals(2, captured.list.size());
        assertEvent(1, Level.INFO, "Webhook HTTP response received", Map.of("responseStatus", 204));
        assertNoSensitiveData();
    }

    private void assertEvent(int index, Level level, String message, Map<String, Object> extraFields) {
        ILoggingEvent event = captured.list.get(index);
        assertEquals(level, event.getLevel());
        assertEquals(message, event.getFormattedMessage());
        Map<String, Object> fields = event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
        assertEquals(2 + extraFields.size(), fields.size());
        assertEquals(work.id(), fields.get("deliveryId"));
        assertEquals(work.attemptId(), fields.get("attemptId"));
        extraFields.forEach((key, value) -> assertEquals(value, fields.get(key)));
    }

    private void assertNoSensitiveData() {
        for (ILoggingEvent event : captured.list) {
            String logged = event.getFormattedMessage() + event.getKeyValuePairs()
                    + java.util.Arrays.toString(event.getArgumentArray()) + event.getMDCPropertyMap();
            for (String sensitive : new String[]{work.url(), work.signingSecret(), work.previousSigningSecret(),
                    "privatePayload", "customer-sensitive-data", "private-interruption-message", "private-storage-message"}) {
                assertFalse(logged.contains(sensitive), () -> "Sensitive value leaked in log: " + event.getMessage());
            }
            assertNull(event.getThrowableProxy(), "Raw exceptions must not be attached to these send logs");
        }
    }
}
