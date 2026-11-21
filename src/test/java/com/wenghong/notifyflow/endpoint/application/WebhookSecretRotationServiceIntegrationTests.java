package com.wenghong.notifyflow.endpoint.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, WebhookSecretRotationService.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebhookSecretRotationServiceIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");
    private static final String ORIGINAL = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String NEXT = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String THIRD = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

    @Autowired
    private WebhookSecretRotationService service;
    @Autowired
    private WebhookEndpointRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private WebhookSecretGenerator generator;
    @MockitoBean
    private Clock clock;

    @Autowired private TenantRepository tenants;
    private Tenant tenant;
    private TenantAccess access;

    @BeforeEach
    void fixedTimeAndGeneratedSecret() {
        tenant = tenants.saveAndFlush(new Tenant("Company A", NOW));
        access = new TenantAccess(tenant.getId(), TenantRole.ADMIN);
        when(clock.instant()).thenReturn(NOW);
        when(generator.generate()).thenReturn(NEXT);
    }

    @AfterEach
    void cleanIsolatedDatabase() {
        repository.deleteAllInBatch();
        tenants.deleteAllInBatch();
    }

    @Test
    void rotationCommitsThroughDirtyChecking() {
        UUID id = saveEndpoint();
        assertEquals(0L, version(id));

        service.rotate(access, id).orElseThrow();

        WebhookEndpoint reloaded = repository.findById(id).orElseThrow();
        assertEquals(NEXT, reloaded.getSigningSecret());
        assertEquals(ORIGINAL, reloaded.getPreviousSigningSecret());
        assertEquals(NOW.plusSeconds(3600), reloaded.getPreviousSigningSecretExpiresAt());
        assertEquals(NOW, reloaded.getUpdatedAt());
        assertEquals(NOW.minusSeconds(7200), reloaded.getCreatedAt());
        assertEquals("Orders", reloaded.getName());
        assertEquals("https://example.com/webhook", reloaded.getUrl());
        assertFalse(reloaded.isEnabled());
        assertEquals(1L, version(id));
    }

    @Test
    void staleDetachedUpdateCannotOverwriteRotatedSecrets() {
        UUID id = saveEndpoint();
        WebhookEndpoint stale = repository.findById(id).orElseThrow();
        service.rotate(access, id).orElseThrow();

        stale.changeEnabled(true, NOW.plusSeconds(1));
        assertThrows(OptimisticLockingFailureException.class, () -> repository.saveAndFlush(stale));

        assertRotatedStatePreserved(id);
    }

    @Test
    void staleManagedUpdateFailsAtCommitWithoutOverwritingRotation() throws Exception {
        UUID id = saveEndpoint();
        CountDownLatch loadedBeforeRotation = new CountDownLatch(1);
        CountDownLatch rotationCommitted = new CountDownLatch(1);

        try (var worker = Executors.newSingleThreadExecutor()) {
            var staleUpdate = worker.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        WebhookEndpoint stale = repository.findById(id).orElseThrow();
                        loadedBeforeRotation.countDown();
                        awaitRelease(rotationCommitted);
                        stale.changeEnabled(true, NOW.plusSeconds(1));
                    }));

            try {
                assertTrue(loadedBeforeRotation.await(5, TimeUnit.SECONDS));
                service.rotate(access, id).orElseThrow();
            } finally {
                rotationCommitted.countDown();
            }

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> staleUpdate.get(10, TimeUnit.SECONDS));
            assertInstanceOf(OptimisticLockingFailureException.class, failure.getCause());
        }

        assertRotatedStatePreserved(id);
    }

    @Test
    void missingEndpointDoesNotGenerateSecretOrInsertRow() {
        assertTrue(service.rotate(access, UUID.randomUUID()).isEmpty());
        assertEquals(0, repository.count());
        verifyNoInteractions(generator);
    }

    @Test
    void activeOverlapRejectsSecondRotationAndPreservesCommittedSecrets() {
        UUID id = saveEndpoint();
        service.rotate(access, id);

        assertThrows(SecretRotationInProgressException.class, () -> service.rotate(access, id));

        WebhookEndpoint reloaded = repository.findById(id).orElseThrow();
        assertEquals(NEXT, reloaded.getSigningSecret());
        assertEquals(ORIGINAL, reloaded.getPreviousSigningSecret());
        assertEquals(NOW.plusSeconds(3600), reloaded.getPreviousSigningSecretExpiresAt());
        verify(generator).generate();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void rotationSucceedsAtOrAfterPreviousExpiry(long secondsAfterExpiry) {
        UUID id = saveEndpoint();
        service.rotate(access, id);
        Instant secondRotation = NOW.plusSeconds(3600 + secondsAfterExpiry);
        when(clock.instant()).thenReturn(secondRotation);
        when(generator.generate()).thenReturn(THIRD);

        service.rotate(access, id);

        WebhookEndpoint reloaded = repository.findById(id).orElseThrow();
        assertEquals(THIRD, reloaded.getSigningSecret());
        assertEquals(NEXT, reloaded.getPreviousSigningSecret());
        assertEquals(secondRotation.plusSeconds(3600), reloaded.getPreviousSigningSecretExpiresAt());
        assertEquals(secondRotation, reloaded.getUpdatedAt());
    }

    @Test
    void outerTransactionRollbackRestoresOriginalSecrets() {
        UUID id = saveEndpoint();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.rotate(access, id).orElseThrow();
            status.setRollbackOnly();
        });

        assertOriginalState(id);
    }

    @Test
    void generatorFailureRollsBackRotation() {
        UUID id = saveEndpoint();
        when(generator.generate()).thenThrow(new IllegalStateException("Test generator failure"));

        assertThrows(IllegalStateException.class, () -> service.rotate(access, id));

        assertOriginalState(id);
    }

    @Test
    void concurrentRotationsProduceOneSuccessAndOneConflict() throws Exception {
        UUID id = saveEndpoint();
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<String> rotate = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                service.rotate(access, id).orElseThrow();
                return "rotated";
            } catch (SecretRotationInProgressException exception) {
                return "conflict";
            }
        };

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(rotate);
            var second = workers.submit(rotate);
            List<String> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(List.of("conflict", "rotated"), outcomes.stream().sorted().toList());
        }

        WebhookEndpoint reloaded = repository.findById(id).orElseThrow();
        assertEquals(NEXT, reloaded.getSigningSecret());
        assertEquals(ORIGINAL, reloaded.getPreviousSigningSecret());
        assertEquals(NOW.plusSeconds(3600), reloaded.getPreviousSigningSecretExpiresAt());
        verify(generator).generate();
    }

    private UUID saveEndpoint() {
        WebhookEndpoint endpoint = new WebhookEndpoint(tenant,
                "Orders", "https://example.com/webhook", NOW.minusSeconds(7200), ORIGINAL);
        endpoint.changeEnabled(false, NOW.minusSeconds(7100));
        return repository.saveAndFlush(endpoint).getId();
    }

    private long version(UUID id) {
        return jdbc.queryForObject("SELECT version FROM webhook_endpoints WHERE id = ?", Long.class, id);
    }

    private void assertRotatedStatePreserved(UUID id) {
        WebhookEndpoint reloaded = repository.findById(id).orElseThrow();
        assertEquals(NEXT, reloaded.getSigningSecret());
        assertEquals(ORIGINAL, reloaded.getPreviousSigningSecret());
        assertEquals(NOW.plusSeconds(3600), reloaded.getPreviousSigningSecretExpiresAt());
        assertEquals(NOW, reloaded.getUpdatedAt());
        assertFalse(reloaded.isEnabled());
        assertEquals(1L, version(id));
    }

    private void awaitRelease(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for rotation to commit");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for rotation", exception);
        }
    }

    private void assertOriginalState(UUID id) {
        WebhookEndpoint reloaded = repository.findById(id).orElseThrow();
        assertEquals(ORIGINAL, reloaded.getSigningSecret());
        assertNull(reloaded.getPreviousSigningSecret());
        assertNull(reloaded.getPreviousSigningSecretExpiresAt());
        assertEquals(NOW.minusSeconds(7100), reloaded.getUpdatedAt());
    }
}
