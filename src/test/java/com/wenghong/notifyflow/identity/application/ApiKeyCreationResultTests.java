package com.wenghong.notifyflow.identity.application;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyCreationResultTests {
    @Test
    void ordinaryObjectLoggingOmitsRawKeyWhileCreationCallerCanAccessIt() {
        UUID id = UUID.randomUUID();
        String raw = "nf_" + "A".repeat(43);
        var result = new ApiKeyCreationResult(id, "Producer " + raw, raw, Instant.parse("2026-11-20T00:00:00Z"));

        assertEquals(raw, result.rawKey());
        assertFalse(result.toString().contains(raw));
        assertFalse(result.toString().contains(result.name()));
        assertTrue(result.toString().contains(id.toString()));
    }
}
