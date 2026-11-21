package com.wenghong.notifyflow.endpoint.application;

import com.wenghong.notifyflow.identity.domain.Tenant;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookSecretRotationServiceTests {

    private final Tenant fixtureTenant = new Tenant("Fixture workspace", Instant.parse("2026-11-20T00:00:00Z"));

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final TenantAccess ACCESS = new TenantAccess(TENANT_ID, TenantRole.ADMIN);
    private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");
    private static final String ORIGINAL = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String NEXT = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String THIRD = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";
    private final WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
    private final WebhookSecretGenerator generator = mock(WebhookSecretGenerator.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final WebhookSecretRotationService service = new WebhookSecretRotationService(
            repository, generator, Clock.fixed(NOW, ZoneOffset.UTC), publisher);
    private final UUID id = UUID.randomUUID();

    @Test
    void viewerIsDeniedBeforeLockingOrGeneratingSecrets() {
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.rotate(new TenantAccess(TENANT_ID, TenantRole.VIEWER), id));
        verifyNoInteractions(repository, generator, publisher);
    }

    @Test
    void missingEndpointReturnsEmptyWithoutGeneratingSecret() {
        when(repository.findByIdAndTenantIdForUpdate(id, TENANT_ID)).thenReturn(Optional.empty());

        assertTrue(service.rotate(ACCESS, id).isEmpty());

        verify(repository).findByIdAndTenantIdForUpdate(id, TENANT_ID);
        verifyNoInteractions(generator, publisher);
    }

    @Test
    void rotatesLockedEndpointWithOneHourOverlap() {
        WebhookEndpoint endpoint = endpoint();
        when(repository.findByIdAndTenantIdForUpdate(id, TENANT_ID)).thenReturn(Optional.of(endpoint));
        when(generator.generate()).thenReturn(NEXT);

        assertSame(endpoint, service.rotate(ACCESS, id).orElseThrow());

        assertEquals(NEXT, endpoint.getSigningSecret());
        assertEquals(ORIGINAL, endpoint.getPreviousSigningSecret());
        assertEquals(NOW.plusSeconds(3600), endpoint.getPreviousSigningSecretExpiresAt());
        assertEquals(NOW, endpoint.getUpdatedAt());
        var order = inOrder(repository, generator);
        order.verify(repository).findByIdAndTenantIdForUpdate(id, TENANT_ID);
        order.verify(generator).generate();
        verify(repository, never()).save(any());
        verify(publisher).publishEvent(new WebhookEndpointChangedEvent(id));
    }

    @Test
    void activeOverlapRejectsRotationWithoutGeneratingAnotherSecret() {
        WebhookEndpoint endpoint = endpoint();
        endpoint.rotateSigningSecret(NEXT, NOW.minusSeconds(60), NOW.plusSeconds(60));
        when(repository.findByIdAndTenantIdForUpdate(id, TENANT_ID)).thenReturn(Optional.of(endpoint));

        SecretRotationInProgressException error = assertThrows(SecretRotationInProgressException.class,
                () -> service.rotate(ACCESS, id));

        assertEquals("A signing secret rotation is already in progress", error.getMessage());
        assertEquals(NEXT, endpoint.getSigningSecret());
        assertEquals(ORIGINAL, endpoint.getPreviousSigningSecret());
        assertEquals(NOW.plusSeconds(60), endpoint.getPreviousSigningSecretExpiresAt());
        assertEquals(NOW.minusSeconds(60), endpoint.getUpdatedAt());
        verifyNoInteractions(generator, publisher);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void expiredOverlapAllowsRotationAtOrAfterBoundary(long nanosPastExpiry) {
        WebhookEndpoint endpoint = endpoint();
        endpoint.rotateSigningSecret(NEXT, NOW.minusSeconds(3600), NOW.minusNanos(nanosPastExpiry));
        when(repository.findByIdAndTenantIdForUpdate(id, TENANT_ID)).thenReturn(Optional.of(endpoint));
        when(generator.generate()).thenReturn(THIRD);

        service.rotate(ACCESS, id);

        assertEquals(THIRD, endpoint.getSigningSecret());
        assertEquals(NEXT, endpoint.getPreviousSigningSecret());
        assertEquals(NOW.plusSeconds(3600), endpoint.getPreviousSigningSecretExpiresAt());
        verify(publisher).publishEvent(new WebhookEndpointChangedEvent(id));
    }

    @Test
    void generatorFailureLeavesEndpointUnchanged() {
        WebhookEndpoint endpoint = endpoint();
        when(repository.findByIdAndTenantIdForUpdate(id, TENANT_ID)).thenReturn(Optional.of(endpoint));
        RuntimeException failure = new IllegalStateException("Test generator failure");
        when(generator.generate()).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.rotate(ACCESS, id)));
        assertEquals(ORIGINAL, endpoint.getSigningSecret());
        assertNull(endpoint.getPreviousSigningSecret());
        assertNull(endpoint.getPreviousSigningSecretExpiresAt());
        assertEquals(NOW.minusSeconds(7200), endpoint.getUpdatedAt());
        verifyNoInteractions(publisher);
    }

    @Test
    void repositoryFailurePropagatesWithoutGeneratingSecret() {
        RuntimeException failure = new org.springframework.dao.DataAccessResourceFailureException("Test failure");
        when(repository.findByIdAndTenantIdForUpdate(id, TENANT_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.rotate(ACCESS, id)));
        verifyNoInteractions(generator, publisher);
    }

    private WebhookEndpoint endpoint() {
        WebhookEndpoint endpoint = new WebhookEndpoint(fixtureTenant,
                "Orders", "https://example.com/webhook", NOW.minusSeconds(7200), ORIGINAL);
        ReflectionTestUtils.setField(endpoint, "id", id);
        return endpoint;
    }
}
