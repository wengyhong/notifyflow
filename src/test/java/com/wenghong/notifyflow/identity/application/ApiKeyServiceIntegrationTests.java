package com.wenghong.notifyflow.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.identity.domain.ApiKey;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.ApiKeyRepository;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, ApiKeyService.class, ApiKeyGenerator.class, ApiKeyHasher.class,
        ApiKeyServiceIntegrationTests.FixedClockConfiguration.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApiKeyServiceIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00.123456Z");
    @Autowired private ApiKeyService service;
    @Autowired private ApiKeyRepository keys;
    @Autowired private TenantRepository tenants;
    @Autowired private ApiKeyHasher hasher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @MockitoSpyBean private Clock clock;
    private Tenant tenant;
    private TenantAccess admin;

    @BeforeEach
    void createTenant() {
        when(clock.instant()).thenReturn(NOW);
        tenant = tenants.saveAndFlush(new Tenant("Company A", NOW));
        admin = new TenantAccess(tenant.getId(), TenantRole.ADMIN);
    }

    @AfterEach
    void cleanCommittedFixtures() {
        // These repositories connect only to this test's disposable PostgreSQL container.
        keys.deleteAllInBatch();
        tenants.deleteAllInBatch();
    }

    @Test
    void creationCommitsOwnedHashOnlyRowBeforeReturningItsRawKey() {
        ApiKeyCreationResult result = service.create(admin, "Order producer");

        assertNotNull(result.id());
        assertTrue(result.rawKey().matches("nf_[A-Za-z0-9_-]{43}"));
        assertEquals("Order producer", result.name());
        assertEquals(NOW, result.createdAt());
        ApiKey stored = keys.findByIdAndTenant_Id(result.id(), tenant.getId()).orElseThrow();
        assertEquals(hasher.hash(result.rawKey()), stored.getKeyHash());
        assertNotEquals(result.rawKey(), stored.getKeyHash());
        assertTrue(stored.isActive());
        assertEquals(result.id(), keys.findByKeyHashAndRevokedAtIsNull(hasher.hash(result.rawKey())).orElseThrow().getId());
        String storedJson = jdbc.queryForObject("SELECT row_to_json(k)::text FROM api_keys k WHERE id = ?", String.class, result.id());
        assertNotNull(storedJson);
        assertFalse(storedJson.contains(result.rawKey()));
        assertEquals(1L, keys.count());
    }

    @Test
    void repeatedCreationGeneratesIndependentKeysBoundToTheSelectedTenant() {
        Tenant other = tenants.saveAndFlush(new Tenant("Company B", NOW));
        ApiKeyCreationResult first = service.create(admin, "Producer");
        ApiKeyCreationResult second = service.create(admin, "Producer");
        ApiKeyCreationResult third = service.create(new TenantAccess(other.getId(), TenantRole.ADMIN), "Producer");

        assertEquals(3, Set.of(first.id(), second.id(), third.id()).size());
        assertEquals(3, Set.of(first.rawKey(), second.rawKey(), third.rawKey()).size());
        assertEquals(3L, keys.count());
        assertTrue(keys.findByIdAndTenant_Id(first.id(), other.getId()).isEmpty());
        assertTrue(keys.findByIdAndTenant_Id(third.id(), tenant.getId()).isEmpty());
        assertEquals(third.id(), keys.findByIdAndTenant_Id(third.id(), other.getId()).orElseThrow().getId());
    }

    @Test
    void viewerCannotCreateAnyRows() {
        assertThrows(AccessDeniedException.class, () -> service.create(
                new TenantAccess(tenant.getId(), TenantRole.VIEWER), "Producer"));
        assertEquals(0L, keys.count());
    }

    @Test
    void outerTransactionRollbackRemovesCreatedKey() {
        transactions.executeWithoutResult(status -> {
            ApiKeyCreationResult result = service.create(admin, "Producer");
            assertTrue(keys.findById(result.id()).isPresent());
            status.setRollbackOnly();
        });
        assertEquals(0L, keys.count());
    }

    @Test
    void missingTenantFailsAtCommitWithoutLeavingAKeyRow() {
        assertThrows(DataIntegrityViolationException.class, () -> service.create(
                new TenantAccess(UUID.randomUUID(), TenantRole.ADMIN), "Producer"));
        assertEquals(0L, keys.count());
    }

    @Test
    void commitFailureDoesNotReturnCreationResultOrLeavePartialRow() {
        assertThrows(DataIntegrityViolationException.class,
                () -> service.create(admin, "n".repeat(101)));
        assertEquals(0L, keys.count());
    }

    @Test
    void revocationCommitsDisablesHashLookupAndPreservesOtherKeys() {
        ApiKeyCreationResult target = service.create(admin, "Target");
        ApiKeyCreationResult other = service.create(admin, "Other");
        String originalHash = hasher.hash(target.rawKey());

        assertTrue(service.revoke(admin, target.id()));

        ApiKey stored = keys.findById(target.id()).orElseThrow();
        assertFalse(stored.isActive());
        assertEquals(NOW, stored.getRevokedAt());
        assertEquals(originalHash, stored.getKeyHash());
        assertEquals(target.name(), stored.getName());
        assertEquals(target.createdAt(), stored.getCreatedAt());
        assertTrue(keys.findByKeyHashAndRevokedAtIsNull(originalHash).isEmpty());
        assertTrue(keys.findByKeyHashAndRevokedAtIsNull(hasher.hash(other.rawKey())).isPresent());
        assertEquals(2L, keys.count());
    }

    @Test
    void repeatedRevocationWithLaterClockPreservesFirstTimestamp() {
        ApiKeyCreationResult key = service.create(admin, "Producer");
        assertTrue(service.revoke(admin, key.id()));
        when(clock.instant()).thenReturn(NOW.plusSeconds(600));

        assertTrue(service.revoke(admin, key.id()));

        assertEquals(NOW, keys.findById(key.id()).orElseThrow().getRevokedAt());
        assertEquals(1L, keys.count());
    }

    @Test
    void viewerCannotRevokeAnOwnedKey() {
        ApiKeyCreationResult key = service.create(admin, "Producer");
        assertThrows(AccessDeniedException.class, () -> service.revoke(
                new TenantAccess(tenant.getId(), TenantRole.VIEWER), key.id()));
        assertTrue(keys.findByKeyHashAndRevokedAtIsNull(hasher.hash(key.rawKey())).isPresent());
    }

    @Test
    void foreignAndMissingKeysReturnFalseWithoutChangingStoredKey() {
        Tenant other = tenants.saveAndFlush(new Tenant("Company B", NOW));
        ApiKeyCreationResult key = service.create(admin, "Producer");
        assertFalse(service.revoke(new TenantAccess(other.getId(), TenantRole.ADMIN), key.id()));
        assertFalse(service.revoke(admin, UUID.randomUUID()));
        assertTrue(keys.findByKeyHashAndRevokedAtIsNull(hasher.hash(key.rawKey())).isPresent());
        assertEquals(1L, keys.count());
    }

    @Test
    void revocationRollbackLeavesKeyActive() {
        ApiKeyCreationResult key = service.create(admin, "Producer");
        transactions.executeWithoutResult(status -> {
            assertTrue(service.revoke(admin, key.id()));
            assertFalse(keys.findById(key.id()).orElseThrow().isActive());
            status.setRollbackOnly();
        });
        assertTrue(keys.findByKeyHashAndRevokedAtIsNull(hasher.hash(key.rawKey())).isPresent());
    }

    @Test
    void concurrentRevocationWaitsForLockedRowAndPreservesFirstCommittedTime() throws Exception {
        ApiKeyCreationResult key = service.create(admin, "Producer");
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(60));
        CountDownLatch firstChanged = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> transactions.execute(status -> {
                boolean revoked = service.revoke(admin, key.id());
                firstChanged.countDown();
                try {
                    if (!releaseFirst.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return revoked;
            }));
            try {
                assertTrue(firstChanged.await(5, TimeUnit.SECONDS));
                var second = workers.submit(() -> service.revoke(admin, key.id()));
                try {
                    // Observe the second SELECT waiting on PostgreSQL's row lock, rather than relying on a sleep.
                    await().atMost(java.time.Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                            SELECT COUNT(*) FROM pg_stat_activity
                            WHERE datname = current_database() AND pid <> pg_backend_pid()
                              AND wait_event_type = 'Lock'
                              AND query ILIKE 'select%' AND query ILIKE '%api_keys%'
                            """, Integer.class) > 0);
                    assertFalse(second.isDone());
                } finally {
                    releaseFirst.countDown();
                }
                assertTrue(first.get(10, TimeUnit.SECONDS));
                assertTrue(second.get(10, TimeUnit.SECONDS));
            } finally {
                releaseFirst.countDown();
            }
        }
        assertEquals(NOW, keys.findById(key.id()).orElseThrow().getRevokedAt());
        assertTrue(keys.findByKeyHashAndRevokedAtIsNull(hasher.hash(key.rawKey())).isEmpty());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
