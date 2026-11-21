package com.wenghong.notifyflow.delivery.domain;

import com.wenghong.notifyflow.identity.domain.Tenant;

import java.time.Instant;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.event.domain.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryTests {

    private final Tenant fixtureTenant = new Tenant("Fixture workspace", Instant.parse("2026-11-20T00:00:00Z"));

    private static final String TEST_SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void newDeliveryStartsInFirstRetryCycle() {
        assertEquals(1, newDelivery().getRetryCycleStartAttempt());
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "FAILED")
    void replayRejectsEveryNonFailedStatusWithoutChangingState(DeliveryStatus status) {
        Delivery delivery = deliveryInStatus(status);
        Instant originalSchedule = delivery.getNextAttemptAt();

        assertThrows(IllegalStateException.class, () -> delivery.requestReplay(6));

        assertEquals(status, delivery.getStatus());
        assertEquals(1, delivery.getRetryCycleStartAttempt());
        assertEquals(originalSchedule, delivery.getNextAttemptAt());
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1})
    void invalidReplayStartLeavesFailedDeliveryUnchanged(int nextAttemptNumber) {
        Delivery delivery = deliveryInStatus(DeliveryStatus.FAILED);

        assertThrows(IllegalArgumentException.class, () -> delivery.requestReplay(nextAttemptNumber));

        assertEquals(DeliveryStatus.FAILED, delivery.getStatus());
        assertEquals(1, delivery.getRetryCycleStartAttempt());
        assertNull(delivery.getNextAttemptAt());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 6})
    void laterReplayCannotMoveCycleStartBackwards(int nextAttemptNumber) {
        Delivery delivery = deliveryInStatus(DeliveryStatus.FAILED);
        delivery.requestReplay(6);
        delivery.tryStartProcessing();
        delivery.markFailed();

        assertThrows(IllegalArgumentException.class, () -> delivery.requestReplay(nextAttemptNumber));

        assertEquals(DeliveryStatus.FAILED, delivery.getStatus());
        assertEquals(6, delivery.getRetryCycleStartAttempt());
    }

    @ParameterizedTest
    @CsvSource({"1, 1, 1", "1, 5, 5", "6, 6, 1", "6, 7, 2", "6, 10, 5", "11, 15, 5"})
    void lifetimeAttemptNumberIsConvertedRelativeToCurrentCycle(
            int cycleStart, int lifetimeNumber, int expected) {
        Delivery delivery = newDelivery();
        if (cycleStart > 1) {
            delivery = deliveryInStatus(DeliveryStatus.FAILED);
            delivery.requestReplay(cycleStart);
        }

        assertEquals(expected, delivery.attemptNumberInCurrentCycle(lifetimeNumber));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1, 5})
    void attemptBeforeCurrentCycleIsRejected(int attemptNumber) {
        Delivery delivery = deliveryInStatus(DeliveryStatus.FAILED);
        delivery.requestReplay(6);

        assertThrows(IllegalArgumentException.class,
                () -> delivery.attemptNumberInCurrentCycle(attemptNumber));

        assertEquals(6, delivery.getRetryCycleStartAttempt());
        assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
    }

    @Test
    void pendingReplayCannotBeRequestedAgain() {
        Delivery delivery = deliveryInStatus(DeliveryStatus.FAILED);
        delivery.requestReplay(6);

        assertThrows(IllegalStateException.class, () -> delivery.requestReplay(7));

        assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
        assertEquals(6, delivery.getRetryCycleStartAttempt());
        assertNull(delivery.getNextAttemptAt());
    }

    @Test
    void failedDeliveryCanStartNewRetryCycle() {
        Delivery delivery = deliveryInStatus(DeliveryStatus.FAILED);

        delivery.requestReplay(6);

        assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
        assertEquals(6, delivery.getRetryCycleStartAttempt());
        assertNull(delivery.getNextAttemptAt());
        assertEquals(1, delivery.attemptNumberInCurrentCycle(6));
        assertEquals(5, delivery.attemptNumberInCurrentCycle(10));
    }

    @Test
    void overdueRetryStartsOnlyOnce() {
        Delivery delivery = newDelivery();
        delivery.tryStartProcessing();
        Instant retryAt = Instant.parse("2026-11-02T00:01:00Z");
        delivery.scheduleRetry(retryAt);

        assertTrue(delivery.tryStartRetry(retryAt.plusSeconds(1)));
        assertFalse(delivery.tryStartRetry(retryAt.plusSeconds(2)));
        assertEquals(DeliveryStatus.PROCESSING, delivery.getStatus());
        assertNull(delivery.getNextAttemptAt());
    }

    @Test
    void nullCurrentTimePreservesRetrySchedule() {
        Delivery delivery = newDelivery();
        delivery.tryStartProcessing();
        Instant retryAt = Instant.parse("2026-11-02T00:01:00Z");
        delivery.scheduleRetry(retryAt);

        assertThrows(NullPointerException.class, () -> delivery.tryStartRetry(null));

        assertEquals(DeliveryStatus.RETRY_SCHEDULED, delivery.getStatus());
        assertEquals(retryAt, delivery.getNextAttemptAt());
    }

    @Test
    void waitingRetryCannotStartAsNewDeliveryOrBeCompleted() {
        Delivery delivery = newDelivery();
        delivery.tryStartProcessing();
        Instant retryAt = Instant.parse("2026-11-02T00:01:00Z");
        delivery.scheduleRetry(retryAt);

        assertFalse(delivery.tryStartProcessing());
        assertThrows(IllegalStateException.class, delivery::markDelivered);
        assertThrows(IllegalStateException.class, delivery::markFailed);
        assertThrows(IllegalStateException.class, () -> delivery.scheduleRetry(retryAt.plusSeconds(60)));
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, delivery.getStatus());
        assertEquals(retryAt, delivery.getNextAttemptAt());
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"PENDING", "PROCESSING", "DELIVERED", "FAILED"})
    void unscheduledDeliveryCannotStartRetry(DeliveryStatus status) {
        Delivery delivery = deliveryInStatus(status);

        assertFalse(delivery.tryStartRetry(Instant.parse("2026-11-02T00:01:00Z")));

        assertEquals(status, delivery.getStatus());
        assertNull(delivery.getNextAttemptAt());
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"PENDING", "DELIVERED", "FAILED"})
    void onlyProcessingDeliveryCanScheduleRetry(DeliveryStatus status) {
        Delivery delivery = deliveryInStatus(status);

        assertThrows(IllegalStateException.class,
                () -> delivery.scheduleRetry(Instant.parse("2026-11-02T00:01:00Z")));

        assertEquals(status, delivery.getStatus());
        assertNull(delivery.getNextAttemptAt());
    }

    @Test
    void scheduledRetryCanStartExactlyAtDueTime() {
        Delivery delivery = newDelivery();
        assertTrue(delivery.tryStartProcessing());
        Instant retryAt = Instant.parse("2026-11-02T00:01:00Z");
        delivery.scheduleRetry(retryAt);

        assertTrue(delivery.tryStartRetry(retryAt));

        assertEquals(DeliveryStatus.PROCESSING, delivery.getStatus());
        assertNull(delivery.getNextAttemptAt());
    }

    @Test
    void scheduledRetryCannotStartBeforeDueTime() {
        Delivery delivery = newDelivery();
        assertTrue(delivery.tryStartProcessing());
        Instant retryAt = Instant.parse("2026-11-02T00:01:00Z");
        delivery.scheduleRetry(retryAt);

        assertFalse(delivery.tryStartRetry(retryAt.minusSeconds(1)));

        assertEquals(DeliveryStatus.RETRY_SCHEDULED, delivery.getStatus());
        assertEquals(retryAt, delivery.getNextAttemptAt());
    }

    @Test
    void nullRetryTimeLeavesDeliveryProcessing() {
        Delivery delivery = newDelivery();
        assertTrue(delivery.tryStartProcessing());

        assertThrows(NullPointerException.class,
                () -> delivery.scheduleRetry(null));

        assertEquals(DeliveryStatus.PROCESSING, delivery.getStatus());
        assertNull(delivery.getNextAttemptAt());
    }

    @Test
    void processingDeliveryCanScheduleRetry() {
        Delivery delivery = newDelivery();
        assertTrue(delivery.tryStartProcessing());
        Instant retryAt = Instant.parse("2026-11-02T00:01:00Z");

        delivery.scheduleRetry(retryAt);

        assertEquals(DeliveryStatus.RETRY_SCHEDULED, delivery.getStatus());
        assertEquals(retryAt, delivery.getNextAttemptAt());
    }

    @Test
    void pendingDeliveryStartsProcessingOnlyOnce() {
        Delivery delivery = newDelivery();

        assertTrue(delivery.tryStartProcessing());
        assertEquals(DeliveryStatus.PROCESSING, delivery.getStatus());

        assertFalse(delivery.tryStartProcessing());
        assertEquals(DeliveryStatus.PROCESSING, delivery.getStatus());
    }

    @Test
    void processingDeliveryCanBeMarkedDelivered() {
        Delivery delivery = newDelivery();
        assertTrue(delivery.tryStartProcessing());

        delivery.markDelivered();

        assertEquals(DeliveryStatus.DELIVERED, delivery.getStatus());
    }

    @Test
    void processingDeliveryCanBeMarkedFailed() {
        Delivery delivery = newDelivery();
        assertTrue(delivery.tryStartProcessing());

        delivery.markFailed();

        assertEquals(DeliveryStatus.FAILED, delivery.getStatus());
    }

    @Test
    void pendingDeliveryCannotBeMarkedDeliveredOrFailed() {
        Delivery delivery = newDelivery();

        assertThrows(IllegalStateException.class, delivery::markDelivered);
        assertEquals(DeliveryStatus.PENDING, delivery.getStatus());

        assertThrows(IllegalStateException.class, delivery::markFailed);
        assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"DELIVERED", "FAILED"})
    void terminalDeliveryCannotRestartOrBeCompletedAgain(DeliveryStatus terminalStatus) {
        Delivery delivery = newDelivery();
        assertTrue(delivery.tryStartProcessing());
        if (terminalStatus == DeliveryStatus.DELIVERED) {
            delivery.markDelivered();
        } else {
            delivery.markFailed();
        }

        assertFalse(delivery.tryStartProcessing());
        assertEquals(terminalStatus, delivery.getStatus());

        assertThrows(IllegalStateException.class, delivery::markDelivered);
        assertEquals(terminalStatus, delivery.getStatus());

        assertThrows(IllegalStateException.class, delivery::markFailed);
        assertEquals(terminalStatus, delivery.getStatus());
    }

    private Delivery deliveryInStatus(DeliveryStatus status) {
        Delivery delivery = newDelivery();
        if (status != DeliveryStatus.PENDING) {
            delivery.tryStartProcessing();
        }
        if (status == DeliveryStatus.DELIVERED) {
            delivery.markDelivered();
        } else if (status == DeliveryStatus.FAILED) {
            delivery.markFailed();
        } else if (status == DeliveryStatus.RETRY_SCHEDULED) {
            delivery.scheduleRetry(Instant.parse("2026-11-02T00:01:00Z"));
        }
        return delivery;
    }

    private Delivery newDelivery() {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        Event event = new Event(fixtureTenant,
                "order.confirmed", JsonMapper.builder().build().readTree("{\"orderNumber\":\"128\"}"),
                "order-128-confirmed", now);
        WebhookEndpoint endpoint = new WebhookEndpoint(fixtureTenant,
                "Warehouse", "https://example.com/webhooks", now, TEST_SIGNING_SECRET);
        return new Delivery(event, endpoint, now);
    }
}
