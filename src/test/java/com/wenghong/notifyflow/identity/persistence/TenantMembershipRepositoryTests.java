package com.wenghong.notifyflow.identity.persistence;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantMembership;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class TenantMembershipRepositoryTests {

    private static final Instant CREATED = Instant.parse("2026-11-20T00:00:00.123456Z");
    private static final String HASH = "encoded-password-test-fixture";

    @Autowired private TenantMembershipRepository memberships;
    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository users;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @EnumSource(TenantRole.class)
    void persistsMembershipAssociationsAndStringRole(TenantRole role) {
        Tenant tenant = tenants.saveAndFlush(new Tenant("Company A", CREATED));
        AppUser user = users.saveAndFlush(new AppUser("alice@example.com", HASH, CREATED));
        UUID id = memberships.saveAndFlush(new TenantMembership(tenant, user, role, CREATED)).getId();
        assertNotNull(id);
        entityManager.clear();

        TenantMembership found = memberships.findByTenant_IdAndUser_Id(tenant.getId(), user.getId()).orElseThrow();

        assertEquals(id, found.getId());
        assertEquals(tenant.getId(), found.getTenant().getId());
        assertEquals(user.getId(), found.getUser().getId());
        assertEquals("Company A", found.getTenant().getName());
        assertEquals("alice@example.com", found.getUser().getEmail());
        assertEquals(role, found.getRole());
        assertEquals(CREATED, found.getCreatedAt());
        assertEquals(role.name(), jdbc.queryForObject(
                "SELECT role FROM tenant_memberships WHERE id = ?", String.class, id));
    }

    @Test
    void sameUserCanHaveDifferentRolesInDifferentTenants() {
        Tenant first = tenants.saveAndFlush(new Tenant("Company A", CREATED));
        Tenant second = tenants.saveAndFlush(new Tenant("Company B", CREATED));
        AppUser user = users.saveAndFlush(new AppUser("alice@example.com", HASH, CREATED));
        TenantMembership admin = memberships.saveAndFlush(
                new TenantMembership(first, user, TenantRole.ADMIN, CREATED));
        TenantMembership viewer = memberships.saveAndFlush(
                new TenantMembership(second, user, TenantRole.VIEWER, CREATED));
        entityManager.clear();

        assertNotEquals(admin.getId(), viewer.getId());
        assertEquals(TenantRole.ADMIN,
                memberships.findByTenant_IdAndUser_Id(first.getId(), user.getId()).orElseThrow().getRole());
        assertEquals(TenantRole.VIEWER,
                memberships.findByTenant_IdAndUser_Id(second.getId(), user.getId()).orElseThrow().getRole());
    }

    @Test
    void differentUsersCanBelongToSameTenant() {
        Tenant tenant = tenants.saveAndFlush(new Tenant("Company A", CREATED));
        AppUser alice = users.saveAndFlush(new AppUser("alice@example.com", HASH, CREATED));
        AppUser bob = users.saveAndFlush(new AppUser("bob@example.com", HASH, CREATED));
        memberships.saveAndFlush(new TenantMembership(tenant, alice, TenantRole.ADMIN, CREATED));
        memberships.saveAndFlush(new TenantMembership(tenant, bob, TenantRole.VIEWER, CREATED));
        entityManager.clear();

        assertEquals(TenantRole.ADMIN,
                memberships.findByTenant_IdAndUser_Id(tenant.getId(), alice.getId()).orElseThrow().getRole());
        assertEquals(TenantRole.VIEWER,
                memberships.findByTenant_IdAndUser_Id(tenant.getId(), bob.getId()).orElseThrow().getRole());
    }

    @Test
    void lookupRequiresMatchingTenantAndUserTogether() {
        Tenant first = tenants.saveAndFlush(new Tenant("Company A", CREATED));
        Tenant second = tenants.saveAndFlush(new Tenant("Company B", CREATED));
        AppUser alice = users.saveAndFlush(new AppUser("alice@example.com", HASH, CREATED));
        AppUser bob = users.saveAndFlush(new AppUser("bob@example.com", HASH, CREATED));
        memberships.saveAndFlush(new TenantMembership(first, alice, TenantRole.ADMIN, CREATED));
        memberships.saveAndFlush(new TenantMembership(second, bob, TenantRole.VIEWER, CREATED));
        entityManager.clear();

        assertTrue(memberships.findByTenant_IdAndUser_Id(first.getId(), bob.getId()).isEmpty());
        assertTrue(memberships.findByTenant_IdAndUser_Id(second.getId(), alice.getId()).isEmpty());
        assertTrue(memberships.findByTenant_IdAndUser_Id(UUID.randomUUID(), alice.getId()).isEmpty());
        assertTrue(memberships.findByTenant_IdAndUser_Id(first.getId(), UUID.randomUUID()).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(TenantRole.class)
    void duplicateUserTenantPairIsRejectedEvenWithDifferentRole(TenantRole secondRole) {
        Tenant tenant = tenants.saveAndFlush(new Tenant("Company A", CREATED));
        AppUser user = users.saveAndFlush(new AppUser("alice@example.com", HASH, CREATED));
        memberships.saveAndFlush(new TenantMembership(tenant, user, TenantRole.ADMIN, CREATED));

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> memberships.saveAndFlush(new TenantMembership(tenant, user, secondRole, CREATED)));

        assertSqlState(error, "23505");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "admin", ""})
    void databaseRejectsRolesOutsideAllowedValues(String role) {
        Tenant tenant = tenants.saveAndFlush(new Tenant("Company A", CREATED));
        AppUser user = users.saveAndFlush(new AppUser("alice@example.com", HASH, CREATED));

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO tenant_memberships (id, tenant_id, user_id, role, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), tenant.getId(), user.getId(), role, Timestamp.from(CREATED)));

        assertSqlState(error, "23514");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseRejectsNonexistentTenantOrUser(boolean missingTenant) {
        Tenant tenant = tenants.saveAndFlush(new Tenant("Company A", CREATED));
        AppUser user = users.saveAndFlush(new AppUser("alice@example.com", HASH, CREATED));
        UUID tenantId = missingTenant ? UUID.randomUUID() : tenant.getId();
        UUID userId = missingTenant ? user.getId() : UUID.randomUUID();

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO tenant_memberships (id, tenant_id, user_id, role, created_at)
                        VALUES (?, ?, ?, 'ADMIN', ?)
                        """, UUID.randomUUID(), tenantId, userId, Timestamp.from(CREATED)));

        assertSqlState(error, "23503");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant_id", "user_id", "role", "created_at"})
    void databaseRequiresMembershipFields(String missing) {
        Tenant tenant = tenants.saveAndFlush(new Tenant("Company A", CREATED));
        AppUser user = users.saveAndFlush(new AppUser("alice@example.com", HASH, CREATED));
        UUID tenantId = missing.equals("tenant_id") ? null : tenant.getId();
        UUID userId = missing.equals("user_id") ? null : user.getId();
        String role = missing.equals("role") ? null : "ADMIN";
        Timestamp created = missing.equals("created_at") ? null : Timestamp.from(CREATED);

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO tenant_memberships (id, tenant_id, user_id, role, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), tenantId, userId, role, created));

        assertSqlState(error, "23502");
    }

    private void assertSqlState(DataIntegrityViolationException error, String expected) {
        SQLException cause = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(expected, cause.getSQLState());
    }
}
