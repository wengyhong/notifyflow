package com.wenghong.notifyflow.endpoint.domain;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookEndpointTests {

    @Test
    void enablingDisabledEndpointUpdatesModificationTimeAndPreservesCreationTime() {
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        Instant enabledAt = createdAt.plusSeconds(120);
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Orders", "https://example.com/webhooks", createdAt);
        endpoint.changeEnabled(false, createdAt.plusSeconds(60));

        endpoint.changeEnabled(true, enabledAt);

        assertTrue(endpoint.isEnabled());
        assertEquals(enabledAt, endpoint.getUpdatedAt());
        assertEquals(createdAt, endpoint.getCreatedAt());
    }

    @Test
    void disablingAlreadyDisabledEndpointPreservesModificationTime() {
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        Instant disabledAt = createdAt.plusSeconds(60);
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Orders", "https://example.com/webhooks", createdAt);
        endpoint.changeEnabled(false, disabledAt);

        endpoint.changeEnabled(false, disabledAt.plusSeconds(60));

        assertFalse(endpoint.isEnabled());
        assertEquals(disabledAt, endpoint.getUpdatedAt());
        assertEquals(createdAt, endpoint.getCreatedAt());
    }

    @Test
    void enablingAlreadyEnabledEndpointPreservesModificationTime() {
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Order notifications", "https://example.com/webhooks/orders", createdAt);

        endpoint.changeEnabled(true, createdAt.plusSeconds(60));

        assertTrue(endpoint.isEnabled());
        assertEquals(createdAt, endpoint.getUpdatedAt());
        assertEquals(createdAt, endpoint.getCreatedAt());
    }

    @Test
    void disablingEndpointUpdatesModificationTimeAndPreservesCreationTime() {
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        Instant modifiedAt = createdAt.plusSeconds(60);
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Order notifications", "https://example.com/webhooks/orders", createdAt);

        endpoint.changeEnabled(false, modifiedAt);

        assertFalse(endpoint.isEnabled());
        assertEquals(modifiedAt, endpoint.getUpdatedAt());
        assertEquals(createdAt, endpoint.getCreatedAt());
    }

    @Test
    void newEndpointStartsEnabledWithMatchingTimestamps() {
        Instant now = Instant.parse("2026-10-28T00:00:00Z");

        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Order notifications", "https://example.com/webhooks/orders", now);

        assertEquals("Order notifications", endpoint.getName());
        assertEquals("https://example.com/webhooks/orders", endpoint.getUrl());
        assertTrue(endpoint.isEnabled());
        assertEquals(now, endpoint.getCreatedAt());
        assertEquals(now, endpoint.getUpdatedAt());
    }
}
