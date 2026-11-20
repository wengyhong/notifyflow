package com.wenghong.notifyflow.identity.security;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.persistence.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DatabaseUserDetailsServiceTests {

    private static final UUID ID = UUID.randomUUID();
    private static final String HASH = "encoded-password-test-fixture";
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final DatabaseUserDetailsService service = new DatabaseUserDetailsService(users);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loadsSecurityPrincipalFromRepositoryIncludingDisabledAccounts(boolean enabled) {
        AppUser user = user();
        ReflectionTestUtils.setField(user, "enabled", enabled);
        when(users.findByEmail("alice@EXAMPLE.com")).thenReturn(Optional.of(user));

        AppUserDetails details = assertInstanceOf(AppUserDetails.class,
                service.loadUserByUsername("alice@EXAMPLE.com"));

        assertEquals(ID, details.getUserId());
        assertEquals("Alice@example.com", details.getUsername());
        assertEquals(HASH, details.getPassword());
        assertEquals(enabled, details.isEnabled());
        assertTrue(details.getAuthorities().isEmpty());
        verify(users).findByEmail("alice@EXAMPLE.com");
        verifyNoMoreInteractions(users);
    }

    @Test
    void missingAccountThrowsUsernameNotFoundException() {
        when(users.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        UsernameNotFoundException error = assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername("missing@example.com"));

        assertEquals("Invalid credentials", error.getMessage());
        verify(users).findByEmail("missing@example.com");
        verifyNoMoreInteractions(users);
    }

    @Test
    void databaseFailureIsNotMisreportedAsUnknownUser() {
        var failure = new DataAccessResourceFailureException("Test database outage");
        when(users.findByEmail("alice@example.com")).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.loadUserByUsername("alice@example.com")));
    }

    @Test
    void eachLookupReturnsFreshPrincipalAfterPreviousCredentialsWereErased() {
        AppUser user = user();
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        AppUserDetails first = (AppUserDetails) service.loadUserByUsername("alice@example.com");
        first.eraseCredentials();

        AppUserDetails second = (AppUserDetails) service.loadUserByUsername("alice@example.com");

        assertNotSame(first, second);
        assertEquals(ID, second.getUserId());
        assertEquals(HASH, second.getPassword());
        assertEquals(HASH, user.getPasswordHash());
    }

    private AppUser user() {
        AppUser user = new AppUser("Alice@example.com", HASH, Instant.parse("2026-11-20T00:00:00Z"));
        ReflectionTestUtils.setField(user, "id", ID);
        return user;
    }
}
