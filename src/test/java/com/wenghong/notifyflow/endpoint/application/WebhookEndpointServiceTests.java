package com.wenghong.notifyflow.endpoint.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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

    private static final String TEST_SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void changeEnabledEnablesDisabledEndpoint() {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, mock(WebhookSecretGenerator.class), mock(WebhookUrlPolicy.class));
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Orders", "https://example.com/webhooks", createdAt, TEST_SIGNING_SECRET);
        endpoint.changeEnabled(false, createdAt.plusSeconds(60));
        when(repository.findById(id)).thenReturn(Optional.of(endpoint));

        Optional<WebhookEndpoint> result = service.changeEnabled(id, true);

        assertSame(endpoint, result.orElseThrow());
        assertTrue(endpoint.isEnabled());
        assertEquals(createdAt, endpoint.getCreatedAt());
        verify(repository).findById(id);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void changeEnabledPreservesTimestampWhenStateAlreadyMatches(boolean enabled) {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, mock(WebhookSecretGenerator.class), mock(WebhookUrlPolicy.class));
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Orders", "https://example.com/webhooks", createdAt, TEST_SIGNING_SECRET);
        endpoint.changeEnabled(enabled, createdAt.plusSeconds(60));
        Instant previousUpdatedAt = endpoint.getUpdatedAt();
        when(repository.findById(id)).thenReturn(Optional.of(endpoint));

        Optional<WebhookEndpoint> result = service.changeEnabled(id, enabled);

        assertSame(endpoint, result.orElseThrow());
        assertEquals(enabled, endpoint.isEnabled());
        assertEquals(previousUpdatedAt, endpoint.getUpdatedAt());
        assertEquals(createdAt, endpoint.getCreatedAt());
        verify(repository).findById(id);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void changeEnabledReturnsEmptyAndDoesNotSaveWhenEndpointIsMissing(boolean enabled) {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, mock(WebhookSecretGenerator.class), mock(WebhookUrlPolicy.class));
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        when(repository.findById(id)).thenReturn(Optional.empty());

        Optional<WebhookEndpoint> result = service.changeEnabled(id, enabled);

        assertTrue(result.isEmpty());
        verify(repository).findById(id);
        verify(repository, never()).save(any(WebhookEndpoint.class));
    }

    @Test
    void changeEnabledDisablesFoundEndpoint() {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, mock(WebhookSecretGenerator.class), mock(WebhookUrlPolicy.class));
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Orders", "https://example.com/webhooks", createdAt, TEST_SIGNING_SECRET);
        when(repository.findById(id)).thenReturn(Optional.of(endpoint));

        Optional<WebhookEndpoint> result = service.changeEnabled(id, false);

        assertSame(endpoint, result.orElseThrow());
        assertFalse(endpoint.isEnabled());
        assertEquals(createdAt, endpoint.getCreatedAt());
        verify(repository).findById(id);
    }

    @Test
    void findAllPassesPaginationToRepositoryAndReturnsItsPage() {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, mock(WebhookSecretGenerator.class), mock(WebhookUrlPolicy.class));
        PageRequest pageable = PageRequest.of(0, 5, Sort.by("name").ascending());
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Orders", "https://example.com/webhooks",
                Instant.parse("2026-10-28T00:00:00Z"), TEST_SIGNING_SECRET);
        Page<WebhookEndpoint> expected = new PageImpl<>(List.of(endpoint), pageable, 1);
        when(repository.findAll(pageable)).thenReturn(expected);

        Page<WebhookEndpoint> result = service.findAll(pageable);

        assertSame(expected, result);
        verify(repository).findAll(pageable);
    }

    @Test
    void findByIdReturnsEndpointWhenFound() {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, mock(WebhookSecretGenerator.class), mock(WebhookUrlPolicy.class));
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Orders", "https://example.com/webhooks",
                Instant.parse("2026-10-28T00:00:00Z"), TEST_SIGNING_SECRET);
        when(repository.findById(id)).thenReturn(Optional.of(endpoint));

        Optional<WebhookEndpoint> result = service.findById(id);

        assertSame(endpoint, result.orElseThrow());
        verify(repository).findById(id);
    }

    @Test
    void findByIdReturnsEmptyWhenEndpointDoesNotExist() {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, mock(WebhookSecretGenerator.class), mock(WebhookUrlPolicy.class));
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        when(repository.findById(id)).thenReturn(Optional.empty());

        Optional<WebhookEndpoint> result = service.findById(id);

        assertTrue(result.isEmpty());
        verify(repository).findById(id);
    }

    @Test
    void createSavesEndpointAndReturnsRepositoryResult() {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookSecretGenerator generator = mock(WebhookSecretGenerator.class);
        WebhookUrlPolicy policy = mock(WebhookUrlPolicy.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, generator, policy);
        String secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        when(generator.generate()).thenReturn(secret);
        WebhookEndpoint savedEndpoint = new WebhookEndpoint(
                "Orders", "https://example.com/webhooks",
                Instant.parse("2026-10-28T00:00:00Z"), TEST_SIGNING_SECRET);

        when(repository.save(any(WebhookEndpoint.class))).thenReturn(savedEndpoint);

        WebhookEndpoint result = service.create("Orders", "https://example.com/webhooks");

        assertSame(savedEndpoint, result);

        ArgumentCaptor<WebhookEndpoint> captor =
                ArgumentCaptor.forClass(WebhookEndpoint.class);
        verify(repository).save(captor.capture());
        WebhookEndpoint submitted = captor.getValue();

        assertEquals("Orders", submitted.getName());
        assertEquals("https://example.com/webhooks", submitted.getUrl());
        assertEquals(secret, submitted.getSigningSecret());
        verify(generator).generate();
        assertTrue(submitted.isEnabled());
        assertNotNull(submitted.getCreatedAt());
        assertEquals(submitted.getCreatedAt(), submitted.getUpdatedAt());

        var order = inOrder(policy, generator, repository);
        order.verify(policy).validate("https://example.com/webhooks");
        order.verify(generator).generate();
        order.verify(repository).save(submitted);
    }

    @Test
    void rejectedUrlDoesNotGenerateSecretOrAccessRepository() {
        WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
        WebhookSecretGenerator generator = mock(WebhookSecretGenerator.class);
        WebhookUrlPolicy policy = mock(WebhookUrlPolicy.class);
        WebhookEndpointService service = new WebhookEndpointService(repository, generator, policy);
        String url = "https://user:password@example.com/webhook";
        InvalidWebhookUrlException rejection = new InvalidWebhookUrlException(
                "Webhook URL must not contain credentials");
        when(policy.validate(url)).thenThrow(rejection);

        InvalidWebhookUrlException error = assertThrows(
                InvalidWebhookUrlException.class, () -> service.create("Orders", url));

        assertSame(rejection, error);
        verify(policy).validate(url);
        verifyNoInteractions(generator, repository);
    }
}
