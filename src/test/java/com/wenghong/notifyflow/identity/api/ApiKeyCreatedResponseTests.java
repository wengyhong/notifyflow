package com.wenghong.notifyflow.identity.api;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyCreatedResponseTests {
    @Test
    void ordinaryObjectLoggingOmitsRawKeyEvenIfNameContainsIt() {
        String raw = "nf_" + "A".repeat(43);
        UUID id = UUID.randomUUID();
        var response = new ApiKeyCreatedResponse(id, "Producer " + raw, raw, Instant.parse("2026-11-21T00:00:00Z"));

        assertEquals(raw, response.rawKey());
        assertFalse(response.toString().contains(raw));
        assertFalse(response.toString().contains(response.name()));
        assertTrue(response.toString().contains(id.toString()));
    }
}
