package com.wenghong.notifyflow.delivery.domain;

import java.time.Instant;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.event.domain.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryTests {

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
        }
        return delivery;
    }

    private Delivery newDelivery() {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        Event event = new Event(
                "order.confirmed", JsonMapper.builder().build().readTree("{\"orderNumber\":\"128\"}"),
                "order-128-confirmed", now);
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Warehouse", "https://example.com/webhooks", now);
        return new Delivery(event, endpoint, now);
    }
}
