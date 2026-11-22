package com.wenghong.notifyflow.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.identity.api.WorkspaceResponse;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantMembership;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.AppUserRepository;
import com.wenghong.notifyflow.identity.persistence.TenantMembershipRepository;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, CurrentUserService.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CurrentUserServiceIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");
    @Autowired private CurrentUserService service;
    @Autowired private AppUserRepository users;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantMembershipRepository memberships;

    @AfterEach
    void cleanIsolatedDatabase() {
        memberships.deleteAllInBatch();
        users.deleteAllInBatch();
        tenants.deleteAllInBatch();
    }

    @Test
    void mapsOnlyTheUsersMembershipsAndLoadsLazyTenantNamesInsideItsOwnTransaction() {
        AppUser alice = users.saveAndFlush(new AppUser("alice@example.com", "test-hash", NOW));
        AppUser bob = users.saveAndFlush(new AppUser("bob@example.com", "test-hash", NOW));
        Tenant orders = tenants.saveAndFlush(new Tenant("Orders", NOW));
        Tenant company = tenants.saveAndFlush(new Tenant("Company", NOW));
        Tenant foreign = tenants.saveAndFlush(new Tenant("Bob only", NOW));
        memberships.saveAllAndFlush(List.of(
                new TenantMembership(orders, alice, TenantRole.ADMIN, NOW),
                new TenantMembership(company, alice, TenantRole.VIEWER, NOW),
                new TenantMembership(orders, bob, TenantRole.VIEWER, NOW),
                new TenantMembership(foreign, bob, TenantRole.ADMIN, NOW)));

        assertEquals(Set.of(
                new WorkspaceResponse(orders.getId(), "Orders", TenantRole.ADMIN),
                new WorkspaceResponse(company.getId(), "Company", TenantRole.VIEWER)),
                Set.copyOf(service.findWorkspaces(alice.getId())));
        assertEquals(Set.of(
                new WorkspaceResponse(orders.getId(), "Orders", TenantRole.VIEWER),
                new WorkspaceResponse(foreign.getId(), "Bob only", TenantRole.ADMIN)),
                Set.copyOf(service.findWorkspaces(bob.getId())));
    }

    @Test
    void returnsEmptyForExistingUserWithoutMembershipsAndUnknownUser() {
        AppUser user = users.saveAndFlush(new AppUser("empty@example.com", "test-hash", NOW));
        assertEquals(List.of(), service.findWorkspaces(user.getId()));
        assertEquals(List.of(), service.findWorkspaces(UUID.randomUUID()));
    }
}
