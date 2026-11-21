package com.wenghong.notifyflow.endpoint.persistence;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.domain.WebhookSubscription;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class WebhookSubscriptionRepositoryTests {

    @Autowired private TenantRepository tenants;
    private Tenant tenant;

    @BeforeEach
    void createFixtureTenant() {
        tenant = tenants.saveAndFlush(new Tenant("Fixture workspace", Instant.parse("2026-11-20T00:00:00Z")));
    }

    private static final String TEST_SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Autowired
    private WebhookSubscriptionRepository subscriptions;

    @Autowired
    private WebhookEndpointRepository endpoints;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void matchingQueryReturnsOnlySelectedTenantsRequestedEventTypeWithEnabledEndpoint() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse");
        WebhookEndpoint email = saveEndpoint("Email");
        WebhookEndpoint finance = saveEndpoint("Finance");
        Tenant other = tenants.saveAndFlush(new Tenant("Company B", Instant.parse("2026-11-20T00:00:00Z")));
        WebhookEndpoint foreign = endpoints.saveAndFlush(new WebhookEndpoint(other,
                "Foreign receiver", "https://example.com/foreign",
                Instant.parse("2026-11-20T00:00:00Z"), TEST_SIGNING_SECRET));
        email.changeEnabled(false, Instant.parse("2026-10-29T00:01:00Z"));

        WebhookSubscription warehouseSubscription =
                new WebhookSubscription(warehouse, "order.confirmed");
        subscriptions.saveAllAndFlush(List.of(
                warehouseSubscription,
                new WebhookSubscription(email, "order.confirmed"),
                new WebhookSubscription(finance, "payment.failed"),
                new WebhookSubscription(foreign, "order.confirmed")));
        entityManager.clear();

        List<WebhookSubscription> matches =
                subscriptions.findAllByEndpoint_Tenant_IdAndEventTypeAndEndpointEnabledTrue(
                        tenant.getId(), "order.confirmed");

        assertEquals(List.of(warehouseSubscription.getId()), matches.stream()
                .map(WebhookSubscription::getId).toList());
        assertEquals(warehouse.getId(), matches.getFirst().getEndpoint().getId());
        assertEquals(List.of(foreign.getId()), subscriptions
                .findAllByEndpoint_Tenant_IdAndEventTypeAndEndpointEnabledTrue(other.getId(), "order.confirmed")
                .stream().map(subscription -> subscription.getEndpoint().getId()).toList());
        assertEquals(List.of(), subscriptions
                .findAllByEndpoint_Tenant_IdAndEventTypeAndEndpointEnabledTrue(UUID.randomUUID(), "order.confirmed"));
    }

    @Test
    void savesAndReloadsSubscriptionWithGeneratedIdAndEndpointRelationship() {
        WebhookEndpoint endpoint = saveEndpoint("Warehouse");
        UUID endpointId = endpoint.getId();
        WebhookSubscription saved = subscriptions.saveAndFlush(
                new WebhookSubscription(endpoint, "order.confirmed"));
        UUID subscriptionId = saved.getId();
        assertNotNull(subscriptionId);

        entityManager.clear();

        WebhookSubscription reloaded = subscriptions.findById(subscriptionId).orElseThrow();
        assertEquals(subscriptionId, reloaded.getId());
        assertEquals("order.confirmed", reloaded.getEventType());
        assertEquals(endpointId, reloaded.getEndpoint().getId());
        // Read another endpoint field to exercise the relationship's lazy load.
        assertEquals("Warehouse", reloaded.getEndpoint().getName());
    }

    @Test
    void rejectsDuplicateEventTypeForSameEndpoint() {
        WebhookEndpoint endpoint = saveEndpoint("Warehouse");
        subscriptions.saveAndFlush(new WebhookSubscription(endpoint, "order.confirmed"));

        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> subscriptions.saveAndFlush(
                        new WebhookSubscription(endpoint, "order.confirmed")));

        assertSqlState(exception, "23505"); // unique_violation
    }

    @Test
    void allowsDifferentEventTypesForSameEndpoint() {
        WebhookEndpoint endpoint = saveEndpoint("Warehouse");
        WebhookSubscription orders = new WebhookSubscription(endpoint, "order.confirmed");
        WebhookSubscription payments = new WebhookSubscription(endpoint, "payment.failed");
        subscriptions.saveAllAndFlush(List.of(orders, payments));
        entityManager.clear();

        assertEquals("order.confirmed",
                subscriptions.findById(orders.getId()).orElseThrow().getEventType());
        assertEquals("payment.failed",
                subscriptions.findById(payments.getId()).orElseThrow().getEventType());
        assertEquals(2L, subscriptions.count());
    }

    @Test
    void allowsSameEventTypeForDifferentEndpoints() {
        WebhookEndpoint warehouse = saveEndpoint("Warehouse");
        WebhookEndpoint email = saveEndpoint("Email service");
        WebhookSubscription first = new WebhookSubscription(warehouse, "order.confirmed");
        WebhookSubscription second = new WebhookSubscription(email, "order.confirmed");
        subscriptions.saveAllAndFlush(List.of(first, second));
        entityManager.clear();

        assertEquals(warehouse.getId(), subscriptions.findById(first.getId())
                .orElseThrow().getEndpoint().getId());
        assertEquals(email.getId(), subscriptions.findById(second.getId())
                .orElseThrow().getEndpoint().getId());
        assertEquals(2L, subscriptions.count());
    }

    @Test
    void databaseRejectsSubscriptionReferencingMissingEndpoint() {
        // JDBC bypasses ORM checks so this verifies PostgreSQL's actual foreign key.
        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                        INSERT INTO webhook_subscriptions (id, endpoint_id, event_type)
                        VALUES (?, ?, ?)
                        """, UUID.randomUUID(), UUID.randomUUID(), "order.confirmed"));

        assertSqlState(exception, "23503"); // foreign_key_violation
    }

    @ParameterizedTest
    @ValueSource(strings = {"endpoint_id", "event_type"})
    void databaseRejectsNullRequiredFields(String nullColumn) {
        WebhookEndpoint endpoint = saveEndpoint("Warehouse");
        UUID endpointId = nullColumn.equals("endpoint_id") ? null : endpoint.getId();
        String eventType = nullColumn.equals("event_type") ? null : "order.confirmed";

        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                        INSERT INTO webhook_subscriptions (id, endpoint_id, event_type)
                        VALUES (?, ?, ?)
                        """, UUID.randomUUID(), endpointId, eventType));

        assertSqlState(exception, "23502"); // not_null_violation
    }

    private WebhookEndpoint saveEndpoint(String name) {
        return endpoints.saveAndFlush(new WebhookEndpoint(tenant,
                name, "https://example.com/webhooks",
                Instant.parse("2026-10-29T00:00:00Z"), TEST_SIGNING_SECRET));
    }

    private void assertSqlState(DataIntegrityViolationException exception, String expected) {
        SQLException cause = assertInstanceOf(SQLException.class, exception.getMostSpecificCause());
        assertEquals(expected, cause.getSQLState());
    }
}
