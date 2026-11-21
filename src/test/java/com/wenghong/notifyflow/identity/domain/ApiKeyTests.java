package com.wenghong.notifyflow.identity.domain;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyTests {
    private static final Instant CREATED = Instant.parse("2026-11-20T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private final Tenant tenant = new Tenant("Company A", CREATED);

    @Test
    void revocationDisablesKeyAndPreservesOriginalTimestampAndIdentity() {
        ApiKey key = new ApiKey(tenant, "Order producer", HASH, CREATED);
        assertTrue(key.isActive());
        assertNull(key.getRevokedAt());
        Instant firstRevocation = CREATED.plusSeconds(60);

        key.revoke(firstRevocation);
        key.revoke(firstRevocation.plusSeconds(3600));

        assertFalse(key.isActive());
        assertEquals(firstRevocation, key.getRevokedAt());
        assertSame(tenant, key.getTenant());
        assertEquals("Order producer", key.getName());
        assertEquals(HASH, key.getKeyHash());
        assertEquals(CREATED, key.getCreatedAt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullRevocationTimeIsRejectedWithoutChangingState(boolean alreadyRevoked) {
        ApiKey key = new ApiKey(tenant, "Order producer", HASH, CREATED);
        Instant revoked = CREATED.plusSeconds(60);
        if (alreadyRevoked) key.revoke(revoked);

        assertThrows(NullPointerException.class, () -> key.revoke(null));

        assertEquals(!alreadyRevoked, key.isActive());
        assertEquals(alreadyRevoked ? revoked : null, key.getRevokedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "name", "keyHash", "createdAt"})
    void constructorRequiresAllCreationValues(String missing) {
        assertThrows(NullPointerException.class, () -> new ApiKey(
                missing.equals("tenant") ? null : tenant,
                missing.equals("name") ? null : "Order producer",
                missing.equals("keyHash") ? null : HASH,
                missing.equals("createdAt") ? null : CREATED));
    }
}
