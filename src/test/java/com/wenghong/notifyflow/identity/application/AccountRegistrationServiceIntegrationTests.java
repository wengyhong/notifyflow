package com.wenghong.notifyflow.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.identity.configuration.PasswordConfiguration;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantMembership;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.AppUserRepository;
import com.wenghong.notifyflow.identity.persistence.TenantMembershipRepository;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import com.wenghong.notifyflow.identity.security.AppUserDetails;
import com.wenghong.notifyflow.identity.security.DatabaseUserDetailsService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, AccountRegistrationService.class, PasswordConfiguration.class,
        DatabaseUserDetailsService.class, AccountRegistrationServiceIntegrationTests.FixedClockConfiguration.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountRegistrationServiceIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final String PASSWORD = "NotifyFlow-test-password-128";

    @Autowired private AccountRegistrationService service;
    @Autowired private AppUserRepository users;
    @Autowired private DatabaseUserDetailsService details;
    @Autowired private TransactionTemplate transactions;
    @Autowired private EntityManager entityManager;
    @MockitoSpyBean private TenantRepository tenants;
    @MockitoSpyBean private TenantMembershipRepository memberships;
    @MockitoSpyBean private PasswordEncoder encoder;

    @AfterEach
    void cleanIsolatedDatabase() {
        memberships.deleteAllInBatch();
        tenants.deleteAllInBatch();
        users.deleteAllInBatch();
    }

    @Test
    void registrationCommitsEncodedPasswordTenantAndAdminMembership() {
        AccountRegistrationResult result = service.register("Alice@example.com", PASSWORD, "Company A");

        AppUser user = users.findById(result.userId()).orElseThrow();
        Tenant tenant = tenants.findById(result.tenantId()).orElseThrow();
        TenantMembership membership = memberships.findByTenant_IdAndUser_Id(
                result.tenantId(), result.userId()).orElseThrow();
        assertEquals("Alice@example.com", user.getEmail());
        assertNotEquals(PASSWORD, user.getPasswordHash());
        assertTrue(encoder.matches(PASSWORD, user.getPasswordHash()));
        assertTrue(user.isEnabled());
        assertEquals("Company A", tenant.getName());
        assertEquals(TenantRole.ADMIN, membership.getRole());
        assertEquals(NOW, user.getCreatedAt());
        assertEquals(NOW, tenant.getCreatedAt());
        assertEquals(NOW, membership.getCreatedAt());
        assertCounts(1);
    }

    @Test
    void registeredUserCanAuthenticateWithConfiguredEncoderAndCaseInsensitiveLookup() {
        AccountRegistrationResult result = service.register("Alice@example.com", PASSWORD, "Company A");
        var provider = new DaoAuthenticationProvider(details);
        provider.setPasswordEncoder(encoder);
        var authentication = new ProviderManager(provider).authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("alice@EXAMPLE.com", PASSWORD));

        assertTrue(authentication.isAuthenticated());
        assertEquals(result.userId(), assertInstanceOf(AppUserDetails.class, authentication.getPrincipal()).getUserId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Alice@example.com", "alice@example.com", "ALICE@EXAMPLE.COM"})
    void duplicateEmailRollsBackNewRegistrationAndPreservesOriginalAccount(String duplicate) {
        AccountRegistrationResult original = service.register("Alice@example.com", PASSWORD, "Original Company");

        assertThrows(EmailAlreadyRegisteredException.class,
                () -> service.register(duplicate, "Another-test-password-999", "Rejected Company"));

        assertCounts(1);
        AppUser user = users.findById(original.userId()).orElseThrow();
        assertTrue(encoder.matches(PASSWORD, user.getPasswordHash()));
        assertEquals("Original Company", tenants.findById(original.tenantId()).orElseThrow().getName());
        assertTrue(memberships.findByTenant_IdAndUser_Id(original.tenantId(), original.userId()).isPresent());
    }

    @Test
    void tenantFailureRollsBackAlreadyFlushedUser() {
        var failure = new IllegalStateException("Test tenant persistence failure");
        doThrow(failure).when(tenants).save(any(Tenant.class));

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.register("alice@example.com", PASSWORD, "Company A")));

        assertCounts(0);
    }

    @Test
    void failureAfterAllThreeInsertsWereFlushedRollsBackAllRows() {
        var failure = new IllegalStateException("Test failure after membership insert");
        doAnswer(invocation -> {
            // Persist through the transaction-bound EntityManager so all three INSERTs
            // really reach PostgreSQL before the simulated downstream failure.
            entityManager.persist(invocation.getArgument(0));
            entityManager.flush();
            assertCounts(1);
            throw failure;
        }).when(memberships).save(any(TenantMembership.class));

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.register("alice@example.com", PASSWORD, "Company A")));

        assertCounts(0);
    }

    @Test
    void enclosingTransactionRollbackDiscardsEntireRegistration() {
        transactions.executeWithoutResult(status -> {
            service.register("alice@example.com", PASSWORD, "Company A");
            entityManager.flush();
            assertCounts(1);
            status.setRollbackOnly();
        });

        assertCounts(0);
    }

    @Test
    void tenantConstraintFailureAtCommitRollsBackUserAndIsNotMisreportedAsDuplicateEmail() {
        assertThrows(DataIntegrityViolationException.class,
                () -> service.register("alice@example.com", PASSWORD, "x".repeat(101)));

        assertCounts(0);
    }

    @Test
    void invalidPasswordCreatesNoRows() {
        assertThrows(InvalidRegistrationPasswordException.class,
                () -> service.register("alice@example.com", "short", "Company A"));

        assertCounts(0);
    }

    @Test
    void concurrentCaseVariantEmailsCreateOnlyOneCompleteAccount() throws Exception {
        CyclicBarrier hashesReady = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object encoded = invocation.callRealMethod();
            // Both requests finish encoding before either can insert its user.
            hashesReady.await(15, TimeUnit.SECONDS);
            return encoded;
        }).when(encoder).encode(any(CharSequence.class));
        List<AccountRegistrationResult> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<AccountRegistrationResult>> requests = List.of(
                    executor.submit(() -> service.register("Alice@example.com", PASSWORD, "Company A")),
                    executor.submit(() -> service.register("ALICE@EXAMPLE.COM", PASSWORD, "Company B")));
            for (var request : requests) {
                try {
                    successes.add(request.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException error) {
                    failures.add(error.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, successes.size());
        assertEquals(1, failures.size());
        assertInstanceOf(EmailAlreadyRegisteredException.class, failures.getFirst());
        assertCounts(1);
        AccountRegistrationResult winner = successes.getFirst();
        assertTrue(users.findById(winner.userId()).isPresent());
        assertTrue(tenants.findById(winner.tenantId()).isPresent());
        assertEquals(TenantRole.ADMIN, memberships.findByTenant_IdAndUser_Id(
                winner.tenantId(), winner.userId()).orElseThrow().getRole());
    }

    private void assertCounts(long expected) {
        assertEquals(expected, users.count());
        assertEquals(expected, tenants.count());
        assertEquals(expected, memberships.count());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
