package com.wenghong.notifyflow.identity.persistence;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Comparator;

import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.identity.domain.ApiKey;
import com.wenghong.notifyflow.identity.domain.Tenant;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class ApiKeyRepositoryTests {
    private static final Instant CREATED = Instant.parse("2026-11-20T00:00:00.123456Z");
    private static final String HASH = "a".repeat(64);
    @Autowired private ApiKeyRepository keys;
    @Autowired private TenantRepository tenants;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    private Tenant tenant;

    @Test
    void listingPaginatesOnlyOwnedKeysIncludingRevokedAndOrdersTimestampTiesById() {
        Tenant other = tenants.saveAndFlush(new Tenant("Company B", CREATED));
        ApiKey old = keys.saveAndFlush(new ApiKey(tenant, "Old", HASH, CREATED));
        ApiKey first = keys.saveAndFlush(new ApiKey(tenant, "Recent active", "b".repeat(64), CREATED.plusSeconds(60)));
        ApiKey second = new ApiKey(tenant, "Recent revoked", "c".repeat(64), CREATED.plusSeconds(60));
        second.revoke(CREATED.plusSeconds(120));
        second = keys.saveAndFlush(second);
        ApiKey foreign = keys.saveAndFlush(new ApiKey(other, "Foreign", "d".repeat(64), CREATED.plusSeconds(180)));
        entityManager.clear();
        var sort = Sort.by(Sort.Direction.DESC, "createdAt", "id");
        var expectedRecentIds = List.of(first, second).stream()
                .sorted(Comparator.comparing((ApiKey key) -> key.getId().toString()).reversed())
                .map(ApiKey::getId).toList();

        var page0 = keys.findAllByTenant_Id(tenant.getId(), PageRequest.of(0, 2, sort));
        var page1 = keys.findAllByTenant_Id(tenant.getId(), PageRequest.of(1, 2, sort));
        assertEquals(expectedRecentIds, page0.getContent().stream().map(ApiKey::getId).toList());
        assertEquals(List.of(old.getId()), page1.getContent().stream().map(ApiKey::getId).toList());
        assertEquals(3, page0.getTotalElements());
        assertEquals(2, page0.getTotalPages());
        assertEquals(1, page0.getContent().stream().filter(key -> !key.isActive()).count());
        assertEquals(0, keys.findAllByTenant_Id(tenant.getId(), PageRequest.of(9, 2, sort)).getNumberOfElements());
        assertEquals(List.of(foreign.getId()), keys.findAllByTenant_Id(other.getId(), PageRequest.of(0, 2, sort))
                .getContent().stream().map(ApiKey::getId).toList());
        assertEquals(0, keys.findAllByTenant_Id(UUID.randomUUID(), PageRequest.of(0, 2, sort)).getTotalElements());
    }

    @BeforeEach
    void createOwner() {
        tenant = tenants.saveAndFlush(new Tenant("Company A", CREATED));
    }

    @Test
    void hashLookupLoadsTheCorrectActiveKeyAndItsTenant() {
        Tenant other = tenants.saveAndFlush(new Tenant("Company B", CREATED));
        ApiKey first = keys.saveAndFlush(new ApiKey(tenant, "Order producer", HASH, CREATED));
        ApiKey second = keys.saveAndFlush(new ApiKey(other, "Order producer", "b".repeat(64), CREATED));
        assertNotNull(first.getId());
        entityManager.clear();

        ApiKey found = keys.findByKeyHashAndRevokedAtIsNull(HASH).orElseThrow();
        assertEquals(first.getId(), found.getId());
        assertEquals(tenant.getId(), found.getTenant().getId());
        assertEquals("Company A", found.getTenant().getName());
        assertEquals("Order producer", found.getName());
        assertEquals(HASH, found.getKeyHash());
        assertEquals(CREATED, found.getCreatedAt());
        assertTrue(found.isActive());
        assertNull(found.getRevokedAt());
        assertEquals(second.getId(), keys.findByKeyHashAndRevokedAtIsNull("b".repeat(64)).orElseThrow().getId());
        assertTrue(keys.findByKeyHashAndRevokedAtIsNull("c".repeat(64)).isEmpty());
    }

    @Test
    void revocationPersistsAndExcludesAuthenticationLookupWhileManagementLookupStillWorks() {
        ApiKey saved = keys.saveAndFlush(new ApiKey(tenant, "Order producer", HASH, CREATED));
        Instant revoked = CREATED.plusSeconds(60);
        saved.revoke(revoked);
        keys.flush();
        entityManager.clear();

        assertTrue(keys.findByKeyHashAndRevokedAtIsNull(HASH).isEmpty());
        ApiKey found = keys.findByIdAndTenant_Id(saved.getId(), tenant.getId()).orElseThrow();
        assertFalse(found.isActive());
        assertEquals(revoked, found.getRevokedAt());
        found.revoke(revoked.plusSeconds(600));
        keys.flush();
        entityManager.clear();
        assertEquals(revoked, keys.findById(saved.getId()).orElseThrow().getRevokedAt());
    }

    @Test
    void managementLookupCannotCrossTenantsOrFindUnknownKeys() {
        Tenant other = tenants.saveAndFlush(new Tenant("Company B", CREATED));
        ApiKey saved = keys.saveAndFlush(new ApiKey(tenant, "Order producer", HASH, CREATED));
        entityManager.clear();

        assertEquals(saved.getId(), keys.findByIdAndTenant_Id(saved.getId(), tenant.getId()).orElseThrow().getId());
        assertTrue(keys.findByIdAndTenant_Id(saved.getId(), other.getId()).isEmpty());
        assertTrue(keys.findByIdAndTenant_Id(saved.getId(), UUID.randomUUID()).isEmpty());
        assertTrue(keys.findByIdAndTenant_Id(UUID.randomUUID(), tenant.getId()).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void hashCannotBeReusedEvenAcrossTenantsOrAfterRevocation(boolean otherTenant, boolean revoked) {
        Tenant secondOwner = otherTenant ? tenants.saveAndFlush(new Tenant("Company B", CREATED)) : tenant;
        ApiKey first = new ApiKey(tenant, "Original", HASH, CREATED);
        if (revoked) first.revoke(CREATED.plusSeconds(60));
        keys.saveAndFlush(first);

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> insert(UUID.randomUUID(), secondOwner.getId(), "Replacement", HASH, CREATED));
        SQLException cause = sqlCause(error, "23505");
        assertTrue(cause.getMessage().contains("uq_api_keys_key_hash"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "tenant_id", "name", "key_hash", "created_at"})
    void databaseRejectsMissingRequiredValues(String missing) {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> insert(missing.equals("id") ? null : UUID.randomUUID(),
                        missing.equals("tenant_id") ? null : tenant.getId(),
                        missing.equals("name") ? null : "Order producer",
                        missing.equals("key_hash") ? null : HASH,
                        missing.equals("created_at") ? null : CREATED));
        SQLException cause = sqlCause(error, "23502");
        assertTrue(cause.getMessage().contains("\"" + missing + "\""));
    }

    @Test
    void databaseRejectsUnknownTenant() {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> insert(UUID.randomUUID(), UUID.randomUUID(), "Order producer", HASH, CREATED));
        SQLException cause = sqlCause(error, "23503");
        assertTrue(cause.getMessage().contains("fk_api_keys_tenants"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "key_hash"})
    void databaseEnforcesConfiguredColumnLengths(String oversized) {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> insert(UUID.randomUUID(), tenant.getId(),
                        oversized.equals("name") ? "n".repeat(101) : "Order producer",
                        oversized.equals("key_hash") ? "a".repeat(65) : HASH, CREATED));
        sqlCause(error, "22001");
    }

    private void insert(UUID id, UUID tenantId, String name, String hash, Instant created) {
        jdbc.update("""
                INSERT INTO api_keys (id, tenant_id, name, key_hash, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, tenantId, name, hash, created == null ? null : Timestamp.from(created));
    }

    private SQLException sqlCause(DataIntegrityViolationException error, String state) {
        SQLException cause = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(state, cause.getSQLState());
        return cause;
    }
}
