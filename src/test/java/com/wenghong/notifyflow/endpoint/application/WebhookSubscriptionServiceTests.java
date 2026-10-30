package com.wenghong.notifyflow.endpoint.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.domain.WebhookSubscription;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.endpoint.persistence.WebhookSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.hibernate.exception.ConstraintViolationException;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebhookSubscriptionServiceTests {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createTranslatesDuplicateConstraintAndPreservesOriginalCause(boolean nested) {
        WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
        WebhookSubscriptionRepository subscriptions = mock(WebhookSubscriptionRepository.class);
        WebhookSubscriptionService service = new WebhookSubscriptionService(endpoints, subscriptions);
        UUID endpointId = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Warehouse", "https://example.com/webhooks",
                Instant.parse("2026-10-29T00:00:00Z"));
        ConstraintViolationException violation = mock(ConstraintViolationException.class);
        when(violation.getConstraintName())
                .thenReturn("uq_webhook_subscriptions_endpoint_event_type");
        Throwable cause = nested ? new IllegalStateException("Persistence wrapper", violation) : violation;
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("Constraint violation", cause);
        when(endpoints.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(subscriptions.saveAndFlush(any(WebhookSubscription.class))).thenThrow(failure);

        DuplicateWebhookSubscriptionException thrown = assertThrows(
                DuplicateWebhookSubscriptionException.class,
                () -> service.create(endpointId, "order.confirmed"));

        assertEquals("The endpoint is already subscribed to this event type.", thrown.getMessage());
        assertSame(failure, thrown.getCause());
        verify(endpoints).findById(endpointId);
        verify(subscriptions).saveAndFlush(any(WebhookSubscription.class));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "webhook_subscriptions_endpoint_id_fkey",
            "another_unique_constraint",
            "uq_webhook_subscriptions_endpoint_event_type_other"
    })
    void createPropagatesIntegrityFailuresForOtherOrUnknownConstraints(String constraintName) {
        WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
        WebhookSubscriptionRepository subscriptions = mock(WebhookSubscriptionRepository.class);
        WebhookSubscriptionService service = new WebhookSubscriptionService(endpoints, subscriptions);
        UUID endpointId = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Warehouse", "https://example.com/webhooks",
                Instant.parse("2026-10-29T00:00:00Z"));
        ConstraintViolationException violation = mock(ConstraintViolationException.class);
        when(violation.getConstraintName()).thenReturn(constraintName);
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("Constraint violation", violation);
        when(endpoints.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(subscriptions.saveAndFlush(any(WebhookSubscription.class))).thenThrow(failure);

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> service.create(endpointId, "order.confirmed"));

        assertSame(failure, thrown);
        verify(endpoints).findById(endpointId);
        verify(subscriptions).saveAndFlush(any(WebhookSubscription.class));
    }

    @Test
    void createSavesSubscriptionForEndpointAndReturnsRepositoryResult() {
        WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
        WebhookSubscriptionRepository subscriptions = mock(WebhookSubscriptionRepository.class);
        WebhookSubscriptionService service = new WebhookSubscriptionService(endpoints, subscriptions);
        UUID endpointId = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Warehouse", "https://example.com/webhooks",
                Instant.parse("2026-10-29T00:00:00Z"));
        WebhookSubscription savedSubscription = new WebhookSubscription(endpoint, "order.confirmed");
        when(endpoints.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(subscriptions.saveAndFlush(any(WebhookSubscription.class)))
                .thenReturn(savedSubscription);

        Optional<WebhookSubscription> result = service.create(endpointId, "order.confirmed");

        assertSame(savedSubscription, result.orElseThrow());
        verify(endpoints).findById(endpointId);
        ArgumentCaptor<WebhookSubscription> captor =
                ArgumentCaptor.forClass(WebhookSubscription.class);
        verify(subscriptions).saveAndFlush(captor.capture());
        WebhookSubscription submitted = captor.getValue();
        assertSame(endpoint, submitted.getEndpoint());
        assertEquals("order.confirmed", submitted.getEventType());
    }

    @Test
    void createPropagatesPersistenceFailure() {
        WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
        WebhookSubscriptionRepository subscriptions = mock(WebhookSubscriptionRepository.class);
        WebhookSubscriptionService service = new WebhookSubscriptionService(endpoints, subscriptions);
        UUID endpointId = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Warehouse", "https://example.com/webhooks",
                Instant.parse("2026-10-29T00:00:00Z"));
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("Duplicate subscription");
        when(endpoints.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(subscriptions.saveAndFlush(any(WebhookSubscription.class))).thenThrow(failure);

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> service.create(endpointId, "order.confirmed"));

        assertSame(failure, thrown);
        verify(endpoints).findById(endpointId);
        verify(subscriptions).saveAndFlush(any(WebhookSubscription.class));
    }

    @Test
    void createReturnsEmptyWithoutSavingWhenEndpointIsMissing() {
        WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
        WebhookSubscriptionRepository subscriptions = mock(WebhookSubscriptionRepository.class);
        WebhookSubscriptionService service = new WebhookSubscriptionService(endpoints, subscriptions);
        UUID endpointId = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        when(endpoints.findById(endpointId)).thenReturn(Optional.empty());

        var result = service.create(endpointId, "order.confirmed");

        assertTrue(result.isEmpty());
        verify(endpoints).findById(endpointId);
        verifyNoInteractions(subscriptions);
    }
}
