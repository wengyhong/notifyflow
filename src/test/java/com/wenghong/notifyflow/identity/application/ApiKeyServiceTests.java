package com.wenghong.notifyflow.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;

import com.wenghong.notifyflow.identity.domain.ApiKey;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.ApiKeyRepository;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class ApiKeyServiceTests {
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID KEY_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final String RAW = "nf_" + "A".repeat(43);
    private static final String HASH = "b".repeat(64);
    private static final TenantAccess ADMIN = new TenantAccess(TENANT_ID, TenantRole.ADMIN);
    private final ApiKeyRepository keys = mock(ApiKeyRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final ApiKeyGenerator generator = mock(ApiKeyGenerator.class);
    private final ApiKeyHasher hasher = mock(ApiKeyHasher.class);
    private final Clock clock = mock(Clock.class);
    private final ApiKeyService service = new ApiKeyService(keys, tenants, generator, hasher, clock);

    @Test
    void administratorStoresHashForVerifiedTenantAndReceivesRawKeyAndSavedMetadata() {
        Tenant tenant = configureTenant();
        when(generator.generate()).thenReturn(RAW);
        when(hasher.hash(RAW)).thenReturn(HASH);
        when(keys.save(any(ApiKey.class))).thenAnswer(call -> {
            ApiKey key = call.getArgument(0);
            ReflectionTestUtils.setField(key, "id", KEY_ID);
            return key;
        });

        ApiKeyCreationResult result = service.create(ADMIN, "Order producer");

        assertEquals(new ApiKeyCreationResult(KEY_ID, "Order producer", RAW, NOW), result);
        ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(keys).save(saved.capture());
        assertSame(tenant, saved.getValue().getTenant());
        assertEquals(HASH, saved.getValue().getKeyHash());
        assertEquals(NOW, saved.getValue().getCreatedAt());
        verify(clock).instant();
        var order = inOrder(tenants, generator, hasher, keys);
        order.verify(tenants).getReferenceById(TENANT_ID);
        order.verify(generator).generate();
        order.verify(hasher).hash(RAW);
        order.verify(keys).save(saved.getValue());
    }

    @Test
    void viewerIsDeniedBeforeGeneratingHashingOrPersisting() {
        assertThrows(AccessDeniedException.class, () -> service.create(
                new TenantAccess(TENANT_ID, TenantRole.VIEWER), "Order producer"));
        verifyNoInteractions(keys, tenants, generator, hasher, clock);
    }

    @Test
    void generationFailureDoesNotHashOrPersist() {
        configureTenant();
        var failure = new IllegalStateException("Test generator failure");
        when(generator.generate()).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.create(ADMIN, "Producer")));
        verifyNoInteractions(hasher, keys);
    }

    @Test
    void hashingFailureDoesNotPersistRawKeyAsFallback() {
        configureTenant();
        when(generator.generate()).thenReturn(RAW);
        var failure = new IllegalStateException("Test hashing failure");
        when(hasher.hash(RAW)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.create(ADMIN, "Producer")));
        verifyNoInteractions(keys);
    }

    @Test
    void persistenceFailurePropagatesInsteadOfReturningAnUnusableKey() {
        configureTenant();
        when(generator.generate()).thenReturn(RAW);
        when(hasher.hash(RAW)).thenReturn(HASH);
        var failure = new DataAccessResourceFailureException("Test database outage");
        when(keys.save(any(ApiKey.class))).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.create(ADMIN, "Producer")));
    }

    private Tenant configureTenant() {
        Tenant tenant = new Tenant("Company A", NOW);
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        when(clock.instant()).thenReturn(NOW);
        when(tenants.getReferenceById(TENANT_ID)).thenReturn(tenant);
        return tenant;
    }

    @Test
    void viewerCannotRevokeOrLookUpAKey() {
        assertThrows(AccessDeniedException.class, () -> service.revoke(
                new TenantAccess(TENANT_ID, TenantRole.VIEWER), KEY_ID));
        verifyNoInteractions(keys, tenants, generator, hasher, clock);
    }

    @Test
    void missingOrForeignKeyReturnsFalseWithoutReadingClockOrGeneratingSecrets() {
        when(keys.findByIdAndTenant_IdForUpdate(KEY_ID, TENANT_ID)).thenReturn(Optional.empty());
        assertFalse(service.revoke(ADMIN, KEY_ID));
        verify(keys).findByIdAndTenant_IdForUpdate(KEY_ID, TENANT_ID);
        verifyNoMoreInteractions(keys);
        verifyNoInteractions(tenants, generator, hasher, clock);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ownedKeyRevocationPreservesFirstTimestampWithoutExplicitSave(boolean alreadyRevoked) {
        ApiKey key = new ApiKey(new Tenant("Company A", NOW), "Producer", HASH, NOW.minusSeconds(120));
        Instant earlier = NOW.minusSeconds(60);
        if (alreadyRevoked) key.revoke(earlier);
        when(keys.findByIdAndTenant_IdForUpdate(KEY_ID, TENANT_ID)).thenReturn(Optional.of(key));
        when(clock.instant()).thenReturn(NOW);

        assertTrue(service.revoke(ADMIN, KEY_ID));

        assertFalse(key.isActive());
        assertEquals(alreadyRevoked ? earlier : NOW, key.getRevokedAt());
        assertEquals(HASH, key.getKeyHash());
        verify(keys).findByIdAndTenant_IdForUpdate(KEY_ID, TENANT_ID);
        verifyNoMoreInteractions(keys);
        verifyNoInteractions(tenants, generator, hasher);
    }

    @Test
    void revocationLookupFailurePropagatesWithoutSideEffects() {
        var failure = new DataAccessResourceFailureException("Test database failure");
        when(keys.findByIdAndTenant_IdForUpdate(KEY_ID, TENANT_ID)).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.revoke(ADMIN, KEY_ID)));
        verifyNoInteractions(tenants, generator, hasher, clock);
    }
}
