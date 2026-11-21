package com.wenghong.notifyflow.identity.security;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.wenghong.notifyflow.identity.application.ApiKeyHasher;
import com.wenghong.notifyflow.identity.domain.ApiKey;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.persistence.ApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ApiKeyAuthenticationServiceTests {
    private static final String RAW = "nf_" + "A".repeat(43);
    private static final String HASH = "b".repeat(64);
    private static final UUID KEY_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private final ApiKeyHasher hasher = mock(ApiKeyHasher.class);
    private final ApiKeyRepository keys = mock(ApiKeyRepository.class);
    private final ApiKeyAuthenticationService service = new ApiKeyAuthenticationService(hasher, keys);

    @ParameterizedTest
    @MethodSource("malformedKeys")
    void malformedKeyIsRejectedBeforeHashingOrLookup(String rawKey) {
        assertTrue(service.authenticate(rawKey).isEmpty());
        verifyNoInteractions(hasher, keys);
    }

    @ParameterizedTest
    @MethodSource("wellFormedKeys")
    void authenticatesUsingHashAndDatabaseOwnerWithoutExposingCredential(String rawKey) {
        Instant now = Instant.parse("2026-11-21T00:00:00Z");
        Tenant tenant = new Tenant("Company A", now);
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        ApiKey key = new ApiKey(tenant, "Producer", HASH, now);
        ReflectionTestUtils.setField(key, "id", KEY_ID);
        when(hasher.hash(rawKey)).thenReturn(HASH);
        when(keys.findByKeyHashAndRevokedAtIsNull(HASH)).thenReturn(Optional.of(key));

        ApiKeyPrincipal principal = service.authenticate(rawKey).orElseThrow();

        assertEquals(new ApiKeyPrincipal(KEY_ID, TENANT_ID), principal);
        assertFalse(principal.toString().contains(rawKey));
        assertFalse(principal.toString().contains(HASH));
        verify(hasher).hash(rawKey);
        verify(keys).findByKeyHashAndRevokedAtIsNull(HASH);
        verifyNoMoreInteractions(hasher, keys);
    }

    @Test
    void noActiveMatchingHashReturnsNoPrincipal() {
        when(hasher.hash(RAW)).thenReturn(HASH);
        when(keys.findByKeyHashAndRevokedAtIsNull(HASH)).thenReturn(Optional.empty());

        assertTrue(service.authenticate(RAW).isEmpty());
        verify(keys).findByKeyHashAndRevokedAtIsNull(HASH);
        verifyNoMoreInteractions(keys);
    }

    @Test
    void hashingFailurePropagatesWithoutTryingRawKeyLookup() {
        var failure = new IllegalStateException("Test hashing failure");
        when(hasher.hash(RAW)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.authenticate(RAW)));
        verifyNoInteractions(keys);
    }

    @Test
    void databaseOutageIsNotReportedAsInvalidCredentials() {
        var failure = new DataAccessResourceFailureException("Test database outage");
        when(hasher.hash(RAW)).thenReturn(HASH);
        when(keys.findByKeyHashAndRevokedAtIsNull(HASH)).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.authenticate(RAW)));
    }

    static Stream<String> wellFormedKeys() {
        return Stream.of(RAW, "nf_" + "z".repeat(42) + "A", "nf_" + "-_".repeat(21) + "A");
    }

    static Stream<String> malformedKeys() {
        return Stream.of(null, "", " ", "not-a-key", "nf_", "nf_" + "A".repeat(42),
                "nf_" + "A".repeat(44), "NF_" + "A".repeat(43), "A".repeat(43),
                "nf_" + "A".repeat(42) + "+", "nf_" + "A".repeat(42) + "/",
                RAW + "=", RAW + " ", " " + RAW, RAW + "\n", "nf_" + "A".repeat(8192));
    }
}
