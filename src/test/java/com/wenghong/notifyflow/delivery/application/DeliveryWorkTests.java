package com.wenghong.notifyflow.delivery.application;

import java.util.UUID;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryWorkTests {

    @Test
    void textRepresentationOmitsBothSecretsWhileAccessorsPreserveThem() {
        UUID deliveryId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String secret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
        String previous = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        Instant expiry = Instant.parse("2026-11-17T01:00:00Z");
        DeliveryWork work = new DeliveryWork(deliveryId, attemptId,
                "https://example.com/webhook", JsonMapper.builder().build().readTree("{}"),
                secret, previous, expiry);

        String text = work.toString();

        assertFalse(text.contains(secret));
        assertFalse(text.contains(previous));
        assertFalse(text.contains("signingSecret"));
        assertTrue(text.contains(deliveryId.toString()));
        assertTrue(text.contains(attemptId.toString()));
        assertEquals(secret, work.signingSecret());
        assertEquals(previous, work.previousSigningSecret());
        assertEquals(expiry, work.previousSigningSecretExpiresAt());
    }
}
