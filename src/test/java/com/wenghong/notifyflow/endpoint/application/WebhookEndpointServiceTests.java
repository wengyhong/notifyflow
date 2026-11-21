package com.wenghong.notifyflow.endpoint.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

class WebhookEndpointServiceTests {

    private final Tenant fixtureTenant = new Tenant("Fixture workspace", Instant.parse("2026-11-20T00:00:00Z"));

    private static final String TEST_SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final TenantAccess ADMIN = new TenantAccess(TENANT_ID, TenantRole.ADMIN);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void viewerCannotChangeEnabledOrTriggerAnySideEffects(boolean enabled) {
        var repository = mock(WebhookEndpointRepository.class);
        var generator = mock(WebhookSecretGenerator.class);
        var policy = mock(WebhookUrlPolicy.class);
        var service = new WebhookEndpointService(repository, generator, policy, publisher, tenants);

        assertThrows(AccessDeniedException.class, () -> service.changeEnabled(
                new TenantAccess(TENANT_ID, TenantRole.VIEWER), UUID.randomUUID(), enabled));
        verifyNoInteractions(repository, publisher, generator, policy, tenants);
    }

    @Test
    void changeEnabledEnablesDisabledEndpoint() {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, mock(WebhookSecretGenerator.class), mock(WebhookUrlPolicy.class), publisher, tenants);
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = new WebhookEndpoint(fixtureTenant,
                "Orders", "https://example.com/webhooks", createdAt, TEST_SIGNING_SECRET);
        endpoint.changeEnabled(false, createdAt.plusSeconds(60));
        ReflectionTestUtils.setField(endpoint, "id", id);
        when(repository.findByIdAndTenant_Id(id, TENANT_ID)).thenReturn(Optional.of(endpoint));

        Optional<WebhookEndpoint> result = service.changeEnabled(ADMIN, id, true);

        assertSame(endpoint, result.orElseThrow());
        assertTrue(endpoint.isEnabled());
        assertEquals(createdAt, endpoint.getCreatedAt());
        verify(repository).findByIdAndTenant_Id(id, TENANT_ID);
        verify(publisher).publishEvent(new WebhookEndpointChangedEvent(id));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void changeEnabledPreservesTimestampWhenStateAlreadyMatches(boolean enabled) {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, mock(WebhookSecretGenerator.class), mock(WebhookUrlPolicy.class), publisher, tenants);
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = new WebhookEndpoint(fixtureTenant,
                "Orders", "https://example.com/webhooks", createdAt, TEST_SIGNING_SECRET);
        endpoint.changeEnabled(enabled, createdAt.plusSeconds(60));
        Instant previousUpdatedAt = endpoint.getUpdatedAt();
        when(repository.findByIdAndTenant_Id(id, TENANT_ID)).thenReturn(Optional.of(endpoint));

        Optional<WebhookEndpoint> result = service.changeEnabled(ADMIN, id, enabled);

        assertSame(endpoint, result.orElseThrow());
        assertEquals(enabled, endpoint.isEnabled());
        assertEquals(previousUpdatedAt, endpoint.getUpdatedAt());
        assertEquals(createdAt, endpoint.getCreatedAt());
        verify(repository).findByIdAndTenant_Id(id, TENANT_ID);
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void changeEnabledReturnsEmptyAndDoesNotSaveWhenEndpointIsMissing(boolean enabled) {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, mock(WebhookSecretGenerator.class), mock(WebhookUrlPolicy.class), publisher, tenants);
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        when(repository.findByIdAndTenant_Id(id, TENANT_ID)).thenReturn(Optional.empty());

        Optional<WebhookEndpoint> result = service.changeEnabled(ADMIN, id, enabled);

        assertTrue(result.isEmpty());
        verify(repository).findByIdAndTenant_Id(id, TENANT_ID);
        verify(repository, never()).save(any(WebhookEndpoint.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void changeEnabledDisablesFoundEndpoint() {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, mock(WebhookSecretGenerator.class), mock(WebhookUrlPolicy.class), publisher, tenants);
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = new WebhookEndpoint(fixtureTenant,
                "Orders", "https://example.com/webhooks", createdAt, TEST_SIGNING_SECRET);
        ReflectionTestUtils.setField(endpoint, "id", id);
        when(repository.findByIdAndTenant_Id(id, TENANT_ID)).thenReturn(Optional.of(endpoint));

        Optional<WebhookEndpoint> result = service.changeEnabled(ADMIN, id, false);

        assertSame(endpoint, result.orElseThrow());
        assertFalse(endpoint.isEnabled());
        assertEquals(createdAt, endpoint.getCreatedAt());
        verify(repository).findByIdAndTenant_Id(id, TENANT_ID);
        verify(publisher).publishEvent(new WebhookEndpointChangedEvent(id));
    }

    @ParameterizedTest
    @EnumSource(TenantRole.class)
    void findAllPassesTenantAndPaginationToRepositoryAndReturnsItsPage(TenantRole role) {
        TenantAccess access = new TenantAccess(TENANT_ID, role);
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, mock(WebhookSecretGenerator.class), mock(WebhookUrlPolicy.class), publisher, tenants);
        PageRequest pageable = PageRequest.of(0, 5, Sort.by("name").ascending());
        WebhookEndpoint endpoint = new WebhookEndpoint(fixtureTenant,
                "Orders", "https://example.com/webhooks",
                Instant.parse("2026-10-28T00:00:00Z"), TEST_SIGNING_SECRET);
        Page<WebhookEndpoint> expected = new PageImpl<>(List.of(endpoint), pageable, 1);
        when(repository.findAllByTenant_Id(TENANT_ID, pageable)).thenReturn(expected);

        Page<WebhookEndpoint> result = service.findAll(access, pageable);

        assertSame(expected, result);
        verify(repository).findAllByTenant_Id(TENANT_ID, pageable);
    }

    @Test
    void createSavesEndpointAndReturnsRepositoryResult() {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookSecretGenerator generator = mock(WebhookSecretGenerator.class);
        WebhookUrlPolicy policy = mock(WebhookUrlPolicy.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, generator, policy, publisher, tenants);
        String secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        Tenant tenant = new Tenant("Company A", Instant.parse("2026-10-28T00:00:00Z"));
        when(tenants.getReferenceById(TENANT_ID)).thenReturn(tenant);
        when(generator.generate()).thenReturn(secret);
        WebhookEndpoint savedEndpoint = new WebhookEndpoint(fixtureTenant,
                "Orders", "https://example.com/webhooks",
                Instant.parse("2026-10-28T00:00:00Z"), TEST_SIGNING_SECRET);

        when(repository.save(any(WebhookEndpoint.class))).thenReturn(savedEndpoint);

        WebhookEndpoint result = service.create(ADMIN, "Orders", "https://example.com/webhooks");

        assertSame(savedEndpoint, result);

        ArgumentCaptor<WebhookEndpoint> captor =
                ArgumentCaptor.forClass(WebhookEndpoint.class);
        verify(repository).save(captor.capture());
        WebhookEndpoint submitted = captor.getValue();

        assertEquals("Orders", submitted.getName());
        assertEquals("https://example.com/webhooks", submitted.getUrl());
        assertEquals(secret, submitted.getSigningSecret());
        assertSame(tenant, submitted.getTenant());
        verify(generator).generate();
        assertTrue(submitted.isEnabled());
        assertNotNull(submitted.getCreatedAt());
        assertEquals(submitted.getCreatedAt(), submitted.getUpdatedAt());

        var order = inOrder(policy, tenants, generator, repository);
        order.verify(policy).validate("https://example.com/webhooks");
        order.verify(tenants).getReferenceById(TENANT_ID);
        order.verify(generator).generate();
        order.verify(repository).save(submitted);
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectedUrlDoesNotGenerateSecretOrAccessRepository() {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookSecretGenerator generator = mock(WebhookSecretGenerator.class);
        WebhookUrlPolicy policy = mock(WebhookUrlPolicy.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, generator, policy, publisher, tenants);
        String url = "https://user:password@example.com/webhook";
        InvalidWebhookUrlException rejection = new InvalidWebhookUrlException(
                "Webhook URL must not contain credentials");
        when(policy.validate(url)).thenThrow(rejection);

        InvalidWebhookUrlException error = assertThrows(
                InvalidWebhookUrlException.class, () -> service.create(ADMIN, "Orders", url));

        assertSame(rejection, error);
        verify(policy).validate(url);
        verifyNoInteractions(generator, repository, tenants);
    }

    @Test
    void viewerCannotCreateBeforeValidationSecretGenerationOrPersistence() {
        var repository = mock(WebhookEndpointRepository.class);
        var generator = mock(WebhookSecretGenerator.class);
        var policy = mock(WebhookUrlPolicy.class);
        var service = new WebhookEndpointService(repository, generator, policy, publisher, tenants);

        assertThrows(AccessDeniedException.class, () -> service.create(
                new TenantAccess(TENANT_ID, TenantRole.VIEWER), "Orders", "https://example.com/webhook"));

        verifyNoInteractions(repository, generator, policy, tenants, publisher);
    }
}
