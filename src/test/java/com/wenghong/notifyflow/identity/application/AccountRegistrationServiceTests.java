package com.wenghong.notifyflow.identity.application;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantMembership;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.AppUserRepository;
import com.wenghong.notifyflow.identity.persistence.TenantMembershipRepository;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountRegistrationServiceTests {

    private static final String PASSWORD = "NotifyFlow-test-password-128";
    private static final String HASH = "encoded-test-password";
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantMembershipRepository memberships = mock(TenantMembershipRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final Clock clock = mock(Clock.class);
    private final AccountRegistrationService service = new AccountRegistrationService(
            users, tenants, memberships, encoder, clock);

    @BeforeEach
    void configureEncodingAndTime() {
        when(clock.instant()).thenReturn(NOW);
        when(encoder.encode(PASSWORD)).thenReturn(HASH);
    }

    @Test
    void createsUserTenantAndServerAssignedAdminMembershipWithOneTimestamp() {
        configureSuccessfulSaves();

        AccountRegistrationResult result = service.register("Alice@example.com", PASSWORD, "Company A");

        assertEquals(new AccountRegistrationResult(USER_ID, TENANT_ID), result);
        var savedUser = ArgumentCaptor.forClass(AppUser.class);
        var savedTenant = ArgumentCaptor.forClass(Tenant.class);
        var savedMembership = ArgumentCaptor.forClass(TenantMembership.class);
        verify(users).saveAndFlush(savedUser.capture());
        verify(tenants).save(savedTenant.capture());
        verify(memberships).save(savedMembership.capture());
        assertEquals("Alice@example.com", savedUser.getValue().getEmail());
        assertEquals(HASH, savedUser.getValue().getPasswordHash());
        assertEquals("Company A", savedTenant.getValue().getName());
        assertSame(savedUser.getValue(), savedMembership.getValue().getUser());
        assertSame(savedTenant.getValue(), savedMembership.getValue().getTenant());
        assertEquals(TenantRole.ADMIN, savedMembership.getValue().getRole());
        assertEquals(NOW, savedUser.getValue().getCreatedAt());
        assertEquals(NOW, savedTenant.getValue().getCreatedAt());
        assertEquals(NOW, savedMembership.getValue().getCreatedAt());
        verify(clock).instant();
        verify(encoder).encode(PASSWORD);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "short", "😀😀😀😀😀😀😀😀😀😀😀😀😀😀"})
    void invalidPasswordFailsBeforeEncodingOrPersistence(String password) {
        assertThrows(InvalidRegistrationPasswordException.class,
                () -> service.register("alice@example.com", password, "Company A"));

        verifyNoInteractions(clock, encoder, users, tenants, memberships);
    }

    @Test
    void oversizedUtf8PasswordFailsBeforeEncodingOrPersistence() {
        assertThrows(InvalidRegistrationPasswordException.class,
                () -> service.register("alice@example.com", "界".repeat(25), "Company A"));

        verifyNoInteractions(clock, encoder, users, tenants, memberships);
    }

    @Test
    void nestedEmailUniqueViolationBecomesApplicationException() {
        var violation = new ConstraintViolationException("Test unique violation",
                new SQLException("Test duplicate email", "23505"), "uq_app_users_email_lower");
        var failure = new DataIntegrityViolationException("Test wrapped insert failure",
                new IllegalStateException("Test wrapper", violation));
        when(users.saveAndFlush(any(AppUser.class))).thenThrow(failure);

        EmailAlreadyRegisteredException thrown = assertThrows(EmailAlreadyRegisteredException.class,
                () -> service.register("alice@example.com", PASSWORD, "Company A"));

        assertSame(failure, thrown.getCause());
        verifyNoInteractions(tenants, memberships);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"app_users_pkey", "unrelated_unique_constraint"})
    void otherConstraintViolationsKeepTheirOriginalMeaning(String constraint) {
        var violation = new ConstraintViolationException("Test constraint failure",
                new SQLException("Test database failure", "23505"), constraint);
        var failure = new DataIntegrityViolationException("Test insert failure", violation);
        when(users.saveAndFlush(any(AppUser.class))).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.register("alice@example.com", PASSWORD, "Company A")));

        verifyNoInteractions(tenants, memberships);
    }

    @Test
    void integrityFailureWithoutHibernateConstraintIsNotCalledDuplicateEmail() {
        var failure = new DataIntegrityViolationException("Test not-null failure",
                new SQLException("Test null column", "23502"));
        when(users.saveAndFlush(any(AppUser.class))).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.register("alice@example.com", PASSWORD, "Company A")));

        verifyNoInteractions(tenants, memberships);
    }

    @Test
    void encoderFailureDoesNotSaveAnyRows() {
        var failure = new IllegalStateException("Test encoder failure");
        when(encoder.encode(PASSWORD)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.register("alice@example.com", PASSWORD, "Company A")));

        verifyNoInteractions(users, tenants, memberships);
    }

    @Test
    void tenantFailureDoesNotAttemptMembershipCreation() {
        configureSuccessfulSaves();
        var failure = new IllegalStateException("Test tenant failure");
        when(tenants.save(any(Tenant.class))).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.register("alice@example.com", PASSWORD, "Company A")));

        verifyNoInteractions(memberships);
    }

    private void configureSuccessfulSaves() {
        when(users.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", USER_ID);
            return user;
        });
        when(tenants.save(any(Tenant.class))).thenAnswer(invocation -> {
            Tenant tenant = invocation.getArgument(0);
            ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
            return tenant;
        });
    }
}
