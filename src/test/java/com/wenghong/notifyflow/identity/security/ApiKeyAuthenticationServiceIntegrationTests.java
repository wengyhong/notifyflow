package com.wenghong.notifyflow.identity.security;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.identity.application.ApiKeyCreationResult;
import com.wenghong.notifyflow.identity.application.ApiKeyGenerator;
import com.wenghong.notifyflow.identity.application.ApiKeyHasher;
import com.wenghong.notifyflow.identity.application.ApiKeyService;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.ApiKeyRepository;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, ApiKeyAuthenticationService.class, ApiKeyService.class,
        ApiKeyGenerator.class, ApiKeyHasher.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApiKeyAuthenticationServiceIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    @Autowired private ApiKeyAuthenticationService authentication;
    @Autowired private ApiKeyService management;
    @Autowired private ApiKeyGenerator generator;
    @Autowired private ApiKeyRepository keys;
    @Autowired private TenantRepository tenants;
    @Autowired private TransactionTemplate transactions;
    @MockitoBean private Clock clock;
    private Tenant tenant;
    private TenantAccess admin;

    @BeforeEach
    void createTenant() {
        when(clock.instant()).thenReturn(NOW);
        tenant = tenants.saveAndFlush(new Tenant("Company A", NOW));
        admin = new TenantAccess(tenant.getId(), TenantRole.ADMIN);
    }

    @AfterEach
    void cleanIsolatedFixtures() {
        keys.deleteAllInBatch();
        tenants.deleteAllInBatch();
    }

    @Test
    void generatedStoredKeyAuthenticatesWithDatabaseIdentityOutsideAnyTestTransaction() {
        ApiKeyCreationResult created = management.create(admin, "Producer");

        // Authentication must open its own transaction and resolve the lazy tenant relationship.
        ApiKeyPrincipal principal = authentication.authenticate(created.rawKey()).orElseThrow();

        assertEquals(created.id(), principal.keyId());
        assertEquals(tenant.getId(), principal.tenantId());
        assertEquals(1L, keys.count());
    }

    @Test
    void revocationRejectsPreviouslyValidKeyWhileAnotherKeyRemainsActive() {
        ApiKeyCreationResult first = management.create(admin, "First");
        ApiKeyCreationResult second = management.create(admin, "Second");
        assertTrue(authentication.authenticate(first.rawKey()).isPresent());

        assertTrue(management.revoke(admin, first.id()));

        assertTrue(authentication.authenticate(first.rawKey()).isEmpty());
        assertEquals(second.id(), authentication.authenticate(second.rawKey()).orElseThrow().keyId());
        assertEquals(2L, keys.count());
    }

    @Test
    void distinctKeysResolveOnlyToTheirStoredTenants() {
        Tenant other = tenants.saveAndFlush(new Tenant("Company B", NOW));
        ApiKeyCreationResult first = management.create(admin, "Producer");
        ApiKeyCreationResult second = management.create(new TenantAccess(other.getId(), TenantRole.ADMIN), "Producer");

        ApiKeyPrincipal firstPrincipal = authentication.authenticate(first.rawKey()).orElseThrow();
        ApiKeyPrincipal secondPrincipal = authentication.authenticate(second.rawKey()).orElseThrow();

        assertEquals(new ApiKeyPrincipal(first.id(), tenant.getId()), firstPrincipal);
        assertEquals(new ApiKeyPrincipal(second.id(), other.getId()), secondPrincipal);
        assertNotEquals(firstPrincipal, secondPrincipal);
    }

    @Test
    void changedWellFormedKeyAndUnknownKeyCannotAuthenticate() {
        ApiKeyCreationResult created = management.create(admin, "Producer");
        String raw = created.rawKey();
        String changed = raw.substring(0, 10) + (raw.charAt(10) == 'A' ? 'B' : 'A') + raw.substring(11);

        assertTrue(authentication.authenticate(changed).isEmpty());
        assertTrue(authentication.authenticate(generator.generate()).isEmpty());
        assertEquals(created.id(), authentication.authenticate(raw).orElseThrow().keyId());
        assertEquals(1L, keys.count());
    }

    @Test
    void rolledBackRevocationRestoresAuthentication() {
        ApiKeyCreationResult created = management.create(admin, "Producer");
        transactions.executeWithoutResult(status -> {
            assertTrue(management.revoke(admin, created.id()));
            assertTrue(authentication.authenticate(created.rawKey()).isEmpty());
            status.setRollbackOnly();
        });

        assertEquals(created.id(), authentication.authenticate(created.rawKey()).orElseThrow().keyId());
    }

    @Test
    void rolledBackCreationDoesNotLeaveAnAuthenticatableKey() {
        ApiKeyCreationResult rolledBack = transactions.execute(status -> {
            ApiKeyCreationResult created = management.create(admin, "Producer");
            status.setRollbackOnly();
            return created;
        });

        assertTrue(authentication.authenticate(rolledBack.rawKey()).isEmpty());
        assertEquals(0L, keys.count());
    }
}
