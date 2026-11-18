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

    @MockitoBean private WebhookEndpointCache cache;
    @MockitoBean private WebhookSecretGenerator generator;
    @MockitoBean private WebhookUrlPolicy policy;
    @MockitoBean private Clock clock;

    @BeforeEach
    void configureRotation() {
        when(clock.instant()).thenReturn(NOW);
        when(generator.generate()).thenReturn(NEXT);
    }

    @AfterEach
    void cleanIsolatedPostgres() {
        repository.deleteAllInBatch();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enabledChangeEvictsOnlyAfterCommit(boolean target) {
        UUID id = saveEndpoint(!target);

        transactions.executeWithoutResult(status -> {
            endpoints.changeEnabled(id, target).orElseThrow();
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
            endpoints.changeEnabled(id, target).orElseThrow();
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

        endpoints.changeEnabled(id, enabled).orElseThrow();

        verifyNoInteractions(cache);
    }

    @Test
    void rotationEvictsOnlyAfterCommit() {
        UUID id = saveEndpoint(true);

        transactions.executeWithoutResult(status -> {
            rotations.rotate(id).orElseThrow();
            verifyNoInteractions(cache);
        });

        WebhookEndpoint stored = repository.findById(id).orElseThrow();
        assertEquals(NEXT, stored.getSigningSecret());
        assertEquals(NOW, stored.getUpdatedAt());
        verify(cache).evict(id);
        verifyNoMoreInteractions(cache);
    }

    @Test
    void rolledBackRotationDoesNotEvict() {
        UUID id = saveEndpoint(true);

        transactions.executeWithoutResult(status -> {
            rotations.rotate(id).orElseThrow();
            verifyNoInteractions(cache);
            status.setRollbackOnly();
        });

        WebhookEndpoint stored = repository.findById(id).orElseThrow();
        assertEquals(ORIGINAL, stored.getSigningSecret());
        assertEquals(NOW.minusSeconds(7200), stored.getUpdatedAt());
        verifyNoInteractions(cache);
    }

    @Test
    void rejectedRotationDoesNotEvict() {
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Warehouse", "https://example.com/webhook", NOW.minusSeconds(7200), ORIGINAL);
        endpoint.rotateSigningSecret(NEXT, NOW.minusSeconds(60), NOW.plusSeconds(60));
        UUID id = repository.saveAndFlush(endpoint).getId();

        assertThrows(SecretRotationInProgressException.class, () -> rotations.rotate(id));

        verifyNoInteractions(cache, generator);
    }

    @Test
    void missingEndpointsDoNotEvict() {
        UUID id = UUID.randomUUID();

        assertTrue(endpoints.changeEnabled(id, false).isEmpty());
        assertTrue(rotations.rotate(id).isEmpty());

        verifyNoInteractions(cache, generator);
    }

    @Test
    void eventWithoutTransactionDoesNotTriggerAfterCommitListener() {
        publisher.publishEvent(new WebhookEndpointChangedEvent(UUID.randomUUID()));

        verifyNoInteractions(cache);
    }

    private UUID saveEndpoint(boolean enabled) {
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Warehouse", "https://example.com/webhook", NOW.minusSeconds(7200), ORIGINAL);
        endpoint.changeEnabled(enabled, NOW.minusSeconds(3600));
        return repository.saveAndFlush(endpoint).getId();
    }
}
