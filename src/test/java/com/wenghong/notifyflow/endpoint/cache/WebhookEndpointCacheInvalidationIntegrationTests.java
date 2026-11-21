package com.wenghong.notifyflow.endpoint.cache;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.application.SecretRotationInProgressException;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointCache;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointChangedEvent;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointService;
import com.wenghong.notifyflow.endpoint.application.WebhookSecretGenerator;
import com.wenghong.notifyflow.endpoint.application.WebhookSecretRotationService;
import com.wenghong.notifyflow.endpoint.application.WebhookUrlPolicy;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import org.springframework.security.access.AccessDeniedException;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, WebhookEndpointService.class,
        WebhookSecretRotationService.class, WebhookEndpointCacheInvalidationListener.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebhookEndpointCacheInvalidationIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");
    private static final String ORIGINAL = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String NEXT = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Autowired private WebhookEndpointService endpoints;
    @Autowired private WebhookSecretRotationService rotations;
    @Autowired private WebhookEndpointRepository repository;
    @Autowired private TransactionTemplate transactions;
    @Autowired private ApplicationEventPublisher publisher;

    @Autowired private TenantRepository tenants;
    private Tenant tenant;
    private TenantAccess access;

    @MockitoBean private WebhookEndpointCache cache;
    @MockitoBean private WebhookSecretGenerator generator;
    @MockitoBean private WebhookUrlPolicy policy;
    @MockitoBean private Clock clock;

    @BeforeEach
    void configureRotation() {
        tenant = tenants.saveAndFlush(new Tenant("Company A", NOW));
        access = new TenantAccess(tenant.getId(), TenantRole.ADMIN);
        when(clock.instant()).thenReturn(NOW);
        when(generator.generate()).thenReturn(NEXT);
    }

    @AfterEach
    void cleanIsolatedPostgres() {
        repository.deleteAllInBatch();
        tenants.deleteAllInBatch();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enabledChangeEvictsOnlyAfterCommit(boolean target) {
        UUID id = saveEndpoint(!target);

        transactions.executeWithoutResult(status -> {
            endpoints.changeEnabled(access, id, target).orElseThrow();
            verifyNoInteractions(cache);
        });

        assertEquals(target, repository.findById(id).orElseThrow().isEnabled());
        verify(cache).evict(id);
        verifyNoMoreInteractions(cache);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rolledBackEnabledChangeDoesNotEvict(boolean target) {
        UUID id = saveEndpoint(!target);

        transactions.executeWithoutResult(status -> {
            endpoints.changeEnabled(access, id, target).orElseThrow();
            verifyNoInteractions(cache);
            status.setRollbackOnly();
        });

        assertEquals(!target, repository.findById(id).orElseThrow().isEnabled());
        verifyNoInteractions(cache);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unchangedEnabledValueDoesNotEvict(boolean enabled) {
        UUID id = saveEndpoint(enabled);

        endpoints.changeEnabled(access, id, enabled).orElseThrow();

        verifyNoInteractions(cache);
    }

    @Test
    void rotationEvictsOnlyAfterCommit() {
        UUID id = saveEndpoint(true);

        transactions.executeWithoutResult(status -> {
            rotations.rotate(access, id).orElseThrow();
            verifyNoInteractions(cache);
        });

        WebhookEndpoint stored = repository.findById(id).orElseThrow();
        assertEquals(NEXT, stored.getSigningSecret());
        assertEquals(NOW, stored.getUpdatedAt());
        verify(cache).evict(id);
        verifyNoMoreInteractions(cache);
    }

    @Test
    void deniedViewerChangePreservesStateTimestampAndCache() {
        UUID id = saveEndpoint(true);
        WebhookEndpoint before = repository.findById(id).orElseThrow();

        assertThrows(AccessDeniedException.class, () -> endpoints.changeEnabled(
                new TenantAccess(tenant.getId(), TenantRole.VIEWER), id, false));

        WebhookEndpoint after = repository.findById(id).orElseThrow();
        assertTrue(after.isEnabled());
        assertEquals(before.getUpdatedAt(), after.getUpdatedAt());
        verifyNoInteractions(cache);
    }

    @Test
    void administratorFromAnotherTenantCannotChangeStateOrEvictCache() {
        UUID id = saveEndpoint(true);
        Tenant other = tenants.saveAndFlush(new Tenant("Company B", NOW));
        WebhookEndpoint before = repository.findById(id).orElseThrow();

        assertTrue(endpoints.changeEnabled(new TenantAccess(other.getId(), TenantRole.ADMIN), id, false).isEmpty());

        WebhookEndpoint after = repository.findById(id).orElseThrow();
        assertTrue(after.isEnabled());
        assertEquals(before.getUpdatedAt(), after.getUpdatedAt());
        verifyNoInteractions(cache);
    }

    @Test
    void rolledBackRotationDoesNotEvict() {
        UUID id = saveEndpoint(true);

        transactions.executeWithoutResult(status -> {
            rotations.rotate(access, id).orElseThrow();
            verifyNoInteractions(cache);
            status.setRollbackOnly();
        });

        WebhookEndpoint stored = repository.findById(id).orElseThrow();
        assertEquals(ORIGINAL, stored.getSigningSecret());
        assertEquals(NOW.minusSeconds(7200), stored.getUpdatedAt());
        verifyNoInteractions(cache);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deniedRotationPreservesBothSecretsAndDoesNotEvictCache(boolean viewer) {
        UUID id = saveEndpoint(true);
        WebhookEndpoint before = repository.findById(id).orElseThrow();
        if (viewer) {
            assertThrows(AccessDeniedException.class, () -> rotations.rotate(
                    new TenantAccess(tenant.getId(), TenantRole.VIEWER), id));
        } else {
            Tenant other = tenants.saveAndFlush(new Tenant("Company B", NOW));
            assertTrue(rotations.rotate(new TenantAccess(other.getId(), TenantRole.ADMIN), id).isEmpty());
        }

        WebhookEndpoint after = repository.findById(id).orElseThrow();
        assertEquals(before.getSigningSecret(), after.getSigningSecret());
        assertEquals(before.getPreviousSigningSecret(), after.getPreviousSigningSecret());
        assertEquals(before.getPreviousSigningSecretExpiresAt(), after.getPreviousSigningSecretExpiresAt());
        assertEquals(before.getUpdatedAt(), after.getUpdatedAt());
        verifyNoInteractions(generator, clock, cache);
    }

    @Test
    void rejectedRotationDoesNotEvict() {
        WebhookEndpoint endpoint = new WebhookEndpoint(tenant,
                "Warehouse", "https://example.com/webhook", NOW.minusSeconds(7200), ORIGINAL);
        endpoint.rotateSigningSecret(NEXT, NOW.minusSeconds(60), NOW.plusSeconds(60));
        UUID id = repository.saveAndFlush(endpoint).getId();

        assertThrows(SecretRotationInProgressException.class, () -> rotations.rotate(access, id));

        verifyNoInteractions(cache, generator);
    }

    @Test
    void missingEndpointsDoNotEvict() {
        UUID id = UUID.randomUUID();

        assertTrue(endpoints.changeEnabled(access, id, false).isEmpty());
        assertTrue(rotations.rotate(access, id).isEmpty());

        verifyNoInteractions(cache, generator);
    }

    @Test
    void eventWithoutTransactionDoesNotTriggerAfterCommitListener() {
        publisher.publishEvent(new WebhookEndpointChangedEvent(UUID.randomUUID()));

        verifyNoInteractions(cache);
    }

    private UUID saveEndpoint(boolean enabled) {
        WebhookEndpoint endpoint = new WebhookEndpoint(tenant,
                "Warehouse", "https://example.com/webhook", NOW.minusSeconds(7200), ORIGINAL);
        endpoint.changeEnabled(enabled, NOW.minusSeconds(3600));
        return repository.saveAndFlush(endpoint).getId();
    }
}
