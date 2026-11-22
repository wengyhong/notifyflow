package com.wenghong.notifyflow.endpoint.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.domain.WebhookSubscription;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.endpoint.persistence.WebhookSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Autowired private TenantRepository tenants;

    @ParameterizedTest
    @EnumSource(TenantRole.class)
    void listingAllowsBothRolesAndDistinguishesEmptyOwnedEndpointsFromForeignOrMissing(TenantRole role) {
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        Tenant owner = tenants.saveAndFlush(new Tenant("Owner", now));
        Tenant other = tenants.saveAndFlush(new Tenant("Other", now));
        WebhookEndpoint endpoint = endpoints.saveAndFlush(new WebhookEndpoint(owner,
                "Selected", "https://example.com/selected", now, TEST_SIGNING_SECRET));
        WebhookEndpoint empty = endpoints.saveAndFlush(new WebhookEndpoint(owner,
                "Empty", "https://example.com/empty", now, TEST_SIGNING_SECRET));
        WebhookEndpoint foreign = endpoints.saveAndFlush(new WebhookEndpoint(other,
                "Foreign", "https://example.com/foreign", now, TEST_SIGNING_SECRET));
        WebhookSubscription own = subscriptions.saveAndFlush(new WebhookSubscription(endpoint, "order.created"));
        subscriptions.saveAndFlush(new WebhookSubscription(foreign, "foreign.created"));
        TenantAccess access = new TenantAccess(owner.getId(), role);

        assertEquals(List.of(own.getId()), service.findAll(access, endpoint.getId()).orElseThrow()
                .stream().map(WebhookSubscription::getId).toList());
        assertEquals(List.of(), service.findAll(access, empty.getId()).orElseThrow());
        assertTrue(service.findAll(access, foreign.getId()).isEmpty());
        assertTrue(service.findAll(access, UUID.randomUUID()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void viewerOrForeignAdministratorCannotCreateASubscription(boolean viewer) {
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        Tenant owner = tenants.saveAndFlush(new Tenant("Company A", now));
        Tenant other = tenants.saveAndFlush(new Tenant("Company B", now));
        WebhookEndpoint endpoint = endpoints.saveAndFlush(new WebhookEndpoint(owner,
                "Orders", "https://example.com/webhook", now, TEST_SIGNING_SECRET));

        if (viewer) {
            assertThrows(AccessDeniedException.class, () -> service.create(
                    new TenantAccess(owner.getId(), TenantRole.VIEWER), endpoint.getId(), "order.confirmed"));
        } else {
            assertTrue(service.create(new TenantAccess(other.getId(), TenantRole.ADMIN),
                    endpoint.getId(), "order.confirmed").isEmpty());
        }
        assertEquals(0L, subscriptions.count());
        assertEquals(now, endpoints.findById(endpoint.getId()).orElseThrow().getUpdatedAt());
    }

    @Test
    void administratorsInDifferentTenantsCanSubscribeTheirOwnEndpointsToTheSameEventType() {
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        Tenant first = tenants.saveAndFlush(new Tenant("Company A", now));
        Tenant second = tenants.saveAndFlush(new Tenant("Company B", now));
        WebhookEndpoint firstEndpoint = endpoints.saveAndFlush(new WebhookEndpoint(first,
                "Orders", "https://example.com/a", now, TEST_SIGNING_SECRET));
        WebhookEndpoint secondEndpoint = endpoints.saveAndFlush(new WebhookEndpoint(second,
                "Orders", "https://example.com/b", now, TEST_SIGNING_SECRET));

        WebhookSubscription firstSubscription = service.create(new TenantAccess(first.getId(), TenantRole.ADMIN),
                firstEndpoint.getId(), "order.confirmed").orElseThrow();
        WebhookSubscription secondSubscription = service.create(new TenantAccess(second.getId(), TenantRole.ADMIN),
                secondEndpoint.getId(), "order.confirmed").orElseThrow();

        assertEquals(first.getId(), firstSubscription.getEndpoint().getTenant().getId());
        assertEquals(second.getId(), secondSubscription.getEndpoint().getTenant().getId());
        assertEquals(2L, subscriptions.count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void duplicateIsTranslatedAndRolledBackWithoutPreventingLaterCreation() {
        Tenant tenant = tenants.saveAndFlush(new Tenant("Company A", Instant.parse("2026-11-20T00:00:00Z")));
        TenantAccess access = new TenantAccess(tenant.getId(), TenantRole.ADMIN);
        UUID endpointId = endpoints.saveAndFlush(new WebhookEndpoint(tenant,
                "Warehouse", "https://example.com/webhooks",
                Instant.parse("2026-10-29T00:00:00Z"), TEST_SIGNING_SECRET)).getId();
        List<UUID> createdSubscriptions = new ArrayList<>();

        try {
            // Each service call must open and finish its own transaction.
            WebhookSubscription first = service.create(access, endpointId, "order.confirmed").orElseThrow();
            createdSubscriptions.add(first.getId());
            assertNotNull(first.getId());
            assertEquals("order.confirmed", subscriptions.findById(first.getId())
                    .orElseThrow().getEventType());

            DuplicateWebhookSubscriptionException duplicate = assertThrows(
                    DuplicateWebhookSubscriptionException.class,
                    () -> service.create(access, endpointId, "order.confirmed"));

            assertInstanceOf(DataIntegrityViolationException.class, duplicate.getCause());
            assertEquals("The endpoint is already subscribed to this event type.",
                    duplicate.getMessage());
            assertEquals(1L, subscriptions.count());

            // A new operation still succeeds after the duplicate transaction rolls back.
            WebhookSubscription second = service.create(access, endpointId, "payment.failed").orElseThrow();
            createdSubscriptions.add(second.getId());
            assertEquals("payment.failed", subscriptions.findById(second.getId())
                    .orElseThrow().getEventType());
            assertEquals(2L, subscriptions.count());
        } finally {
            subscriptions.deleteAllById(createdSubscriptions);
            endpoints.deleteById(endpointId);
            tenants.deleteById(tenant.getId());
        }
    }
}
