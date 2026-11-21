package com.wenghong.notifyflow.endpoint.persistence;

import java.time.Instant;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class WebhookEndpointRepositoryTests {

    private static final String TEST_SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Autowired
    private WebhookEndpointRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TenantRepository tenants;

    private Tenant tenant;

    @BeforeEach
    void createFixtureTenant() {
        tenant = tenants.saveAndFlush(new Tenant("Fixture workspace", Instant.parse("2026-11-20T00:00:00Z")));
    }

    @Test
    void persistsEachEndpointsTenantWithoutMixingOwners() {
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        var first = tenants.saveAndFlush(new Tenant("Company A", now));
        var second = tenants.saveAndFlush(new Tenant("Company B", now));
        var firstEndpoint = repository.saveAndFlush(new WebhookEndpoint(first,
                "Orders", "https://example.com/a", now, TEST_SIGNING_SECRET));
        var secondEndpoint = repository.saveAndFlush(new WebhookEndpoint(second,
                "Orders", "https://example.com/b", now, TEST_SIGNING_SECRET));
        entityManager.clear();

        assertEquals(first.getId(), repository.findById(firstEndpoint.getId()).orElseThrow().getTenant().getId());
        assertEquals(second.getId(), repository.findById(secondEndpoint.getId()).orElseThrow().getTenant().getId());
    }

    @Test
    void databaseRejectsAnUnknownTenant() {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO webhook_endpoints
                            (id, name, url, created_at, updated_at, signing_secret, tenant_id)
                        VALUES (?, 'Orders', 'https://example.com/webhook',
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                        """, UUID.randomUUID(), TEST_SIGNING_SECRET, UUID.randomUUID()));
        SQLException cause = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals("23503", cause.getSQLState());
        assertTrue(cause.getMessage().contains("fk_webhook_endpoints_tenants"));
    }

    @Test
    void tenantScopedLookupReturnsOnlyTheMatchingOwnersEndpoint() {
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        Tenant first = tenants.saveAndFlush(new Tenant("Company A", now));
        Tenant second = tenants.saveAndFlush(new Tenant("Company B", now));
        WebhookEndpoint endpoint = repository.saveAndFlush(new WebhookEndpoint(first,
                "Orders", "https://example.com/a", now, TEST_SIGNING_SECRET));
        entityManager.clear();

        assertEquals(endpoint.getId(), repository.findByIdAndTenant_Id(endpoint.getId(), first.getId())
                .orElseThrow().getId());
        assertTrue(repository.findByIdAndTenant_Id(endpoint.getId(), second.getId()).isEmpty());
        assertTrue(repository.findByIdAndTenant_Id(endpoint.getId(), UUID.randomUUID()).isEmpty());
        assertTrue(repository.findByIdAndTenant_Id(UUID.randomUUID(), first.getId()).isEmpty());
    }

    @Test
    void savesAndReloadsBothSecretsAndOverlapExpiry() {
        Instant created = Instant.parse("2026-11-17T00:00:00Z");
        Instant rotated = created.plusSeconds(60);
        Instant expires = rotated.plusSeconds(3600);
        String nextSecret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        WebhookEndpoint endpoint = repository.saveAndFlush(new WebhookEndpoint(tenant,
                "Orders", "https://example.com/webhook", created, TEST_SIGNING_SECRET));

        endpoint.rotateSigningSecret(nextSecret, rotated, expires);
        repository.flush();
        entityManager.clear();

        WebhookEndpoint reloaded = repository.findById(endpoint.getId()).orElseThrow();
        assertEquals(nextSecret, reloaded.getSigningSecret());
        assertEquals(TEST_SIGNING_SECRET, reloaded.getPreviousSigningSecret());
        assertEquals(expires, reloaded.getPreviousSigningSecretExpiresAt());
        assertEquals(rotated, reloaded.getUpdatedAt());
        assertEquals(created, reloaded.getCreatedAt());
    }

    @Test
    void lockingLookupExcludesEndpointsOwnedByAnotherTenant() {
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        Tenant owner = tenants.saveAndFlush(new Tenant("Company A", now));
        Tenant other = tenants.saveAndFlush(new Tenant("Company B", now));
        WebhookEndpoint endpoint = repository.saveAndFlush(new WebhookEndpoint(owner,
                "Orders", "https://example.com/webhook", now, TEST_SIGNING_SECRET));
        entityManager.clear();

        assertTrue(repository.findByIdAndTenantIdForUpdate(endpoint.getId(), other.getId()).isEmpty());
        assertTrue(repository.findByIdAndTenantIdForUpdate(UUID.randomUUID(), owner.getId()).isEmpty());
        WebhookEndpoint locked = repository.findByIdAndTenantIdForUpdate(endpoint.getId(), owner.getId()).orElseThrow();
        assertEquals(endpoint.getId(), locked.getId());
        assertEquals(owner.getId(), locked.getTenant().getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void databaseRejectsPartiallyPopulatedPreviousSecretPair(boolean missingSecret) {
        String previous = missingSecret ? null : TEST_SIGNING_SECRET;
        java.sql.Timestamp expiry = missingSecret
                ? java.sql.Timestamp.from(Instant.parse("2026-11-17T01:00:00Z")) : null;

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO webhook_endpoints
                            (id, tenant_id, name, url, created_at, updated_at, signing_secret,
                             previous_signing_secret, previous_signing_secret_expires_at)
                        VALUES (?, ?, 'Orders', 'https://example.com/webhook',
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?)
                        """, UUID.randomUUID(), tenant.getId(), TEST_SIGNING_SECRET, previous, expiry));

        SQLException cause = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals("23514", cause.getSQLState());
        assertTrue(cause.getMessage().contains("ck_webhook_endpoints_previous_secret_pair"));
    }

    @Test
    void databaseRejectsEndpointWithoutSigningSecret() {
        DataIntegrityViolationException error = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO webhook_endpoints
                            (id, tenant_id, name, url, created_at, updated_at)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """,
                        UUID.randomUUID(), tenant.getId(), "Orders", "https://example.com/webhook"));

        SQLException cause = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals("23502", cause.getSQLState());
        assertTrue(cause.getMessage().contains("signing_secret"));
    }

    @Test
    void databaseRejectsEndpointWithoutTenant() {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO webhook_endpoints (id, name, url, created_at, updated_at, signing_secret)
                        VALUES (?, 'Orders', 'https://example.com/webhook', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                        """, UUID.randomUUID(), TEST_SIGNING_SECRET));
        SQLException cause = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals("23502", cause.getSQLState());
        assertTrue(cause.getMessage().contains("tenant_id"));
    }

    @Test
    void tenantScopedPagesExcludeForeignEndpointsFromContentAndTotals() {
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        Tenant owner = tenants.saveAndFlush(new Tenant("Company A", now));
        Tenant other = tenants.saveAndFlush(new Tenant("Company B", now));
        WebhookEndpoint older = new WebhookEndpoint(owner,
                "Older", "https://example.com/older", now.minusSeconds(60), TEST_SIGNING_SECRET);
        WebhookEndpoint first = new WebhookEndpoint(owner,
                "First", "https://example.com/first", now, TEST_SIGNING_SECRET);
        WebhookEndpoint second = new WebhookEndpoint(owner,
                "Second", "https://example.com/second", now, TEST_SIGNING_SECRET);
        WebhookEndpoint foreign = new WebhookEndpoint(other,
                "Foreign", "https://example.com/foreign", now.plusSeconds(60), TEST_SIGNING_SECRET);
        repository.saveAllAndFlush(List.of(first, older, second, foreign));

        List<UUID> newestIds = List.of(first, second).stream()
                .sorted(Comparator.comparing((WebhookEndpoint endpoint) ->
                        endpoint.getId().toString()).reversed())
                .map(WebhookEndpoint::getId)
                .toList();
        entityManager.clear();
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt", "id");

        Page<WebhookEndpoint> firstPage = repository.findAllByTenant_Id(owner.getId(), PageRequest.of(0, 2, sort));
        Page<WebhookEndpoint> secondPage = repository.findAllByTenant_Id(owner.getId(), PageRequest.of(1, 2, sort));

        assertEquals(newestIds, firstPage.getContent().stream()
                .map(WebhookEndpoint::getId).toList());
        assertEquals(0, firstPage.getNumber());
        assertEquals(2, firstPage.getSize());
        assertEquals(3L, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(List.of(older.getId()), secondPage.getContent().stream()
                .map(WebhookEndpoint::getId).toList());
        assertEquals(1, secondPage.getNumber());
        assertEquals(3L, secondPage.getTotalElements());
        assertEquals(2, secondPage.getTotalPages());

        Page<WebhookEndpoint> beyondLast = repository.findAllByTenant_Id(owner.getId(), PageRequest.of(5, 2, sort));
        assertTrue(beyondLast.isEmpty());
        assertEquals(3L, beyondLast.getTotalElements());
        assertEquals(2, beyondLast.getTotalPages());

        Page<WebhookEndpoint> otherPage = repository.findAllByTenant_Id(other.getId(), PageRequest.of(0, 2, sort));
        assertEquals(List.of(foreign.getId()), otherPage.map(WebhookEndpoint::getId).getContent());
        assertEquals(1L, otherPage.getTotalElements());

        Page<WebhookEndpoint> unknownTenant = repository.findAllByTenant_Id(UUID.randomUUID(), PageRequest.of(0, 2, sort));
        assertTrue(unknownTenant.isEmpty());
        assertEquals(0L, unknownTenant.getTotalElements());
    }

    @Test
    void savesAndReloadsEndpointWithGeneratedIdAndAllFields() {
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = new WebhookEndpoint(tenant,
                "Order notifications", "https://example.com/webhooks/orders", now, TEST_SIGNING_SECRET);

        WebhookEndpoint saved = repository.saveAndFlush(endpoint);
        UUID id = saved.getId();
        assertNotNull(id);

        // Force findById to read PostgreSQL instead of returning the managed entity.
        entityManager.clear();

        WebhookEndpoint reloaded = repository.findById(id).orElseThrow();

        assertEquals(id, reloaded.getId());
        assertEquals("Order notifications", reloaded.getName());
        assertEquals("https://example.com/webhooks/orders", reloaded.getUrl());
        assertEquals(TEST_SIGNING_SECRET, reloaded.getSigningSecret());
        assertTrue(reloaded.isEnabled());
        assertEquals(now, reloaded.getCreatedAt());
        assertEquals(now, reloaded.getUpdatedAt());
    }
}
