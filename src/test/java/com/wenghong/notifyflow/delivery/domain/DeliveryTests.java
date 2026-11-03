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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryTests {

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
