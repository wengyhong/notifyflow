package com.wenghong.notifyflow.endpoint.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.domain.WebhookSubscription;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.endpoint.persistence.WebhookSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, WebhookSubscriptionService.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class WebhookSubscriptionServiceIntegrationTests {

    private static final String TEST_SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Autowired
    private WebhookEndpointRepository endpoints;

    @Autowired
    private WebhookSubscriptionRepository subscriptions;

    @Autowired
    private WebhookSubscriptionService service;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void duplicateIsTranslatedAndRolledBackWithoutPreventingLaterCreation() {
        UUID endpointId = endpoints.saveAndFlush(new WebhookEndpoint(
                "Warehouse", "https://example.com/webhooks",
                Instant.parse("2026-10-29T00:00:00Z"), TEST_SIGNING_SECRET)).getId();
        List<UUID> createdSubscriptions = new ArrayList<>();

        try {
            // Each service call must open and finish its own transaction.
            WebhookSubscription first = service.create(endpointId, "order.confirmed").orElseThrow();
            createdSubscriptions.add(first.getId());
            assertNotNull(first.getId());
            assertEquals("order.confirmed", subscriptions.findById(first.getId())
                    .orElseThrow().getEventType());

            DuplicateWebhookSubscriptionException duplicate = assertThrows(
                    DuplicateWebhookSubscriptionException.class,
                    () -> service.create(endpointId, "order.confirmed"));

            assertInstanceOf(DataIntegrityViolationException.class, duplicate.getCause());
            assertEquals("The endpoint is already subscribed to this event type.",
                    duplicate.getMessage());
            assertEquals(1L, subscriptions.count());

            // A new operation still succeeds after the duplicate transaction rolls back.
            WebhookSubscription second = service.create(endpointId, "payment.failed").orElseThrow();
            createdSubscriptions.add(second.getId());
            assertEquals("payment.failed", subscriptions.findById(second.getId())
                    .orElseThrow().getEventType());
            assertEquals(2L, subscriptions.count());
        } finally {
            subscriptions.deleteAllById(createdSubscriptions);
            endpoints.deleteById(endpointId);
        }
    }
}
