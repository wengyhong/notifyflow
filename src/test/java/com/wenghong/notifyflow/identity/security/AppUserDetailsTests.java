package com.wenghong.notifyflow.identity.security;

import java.time.Instant;
import java.util.UUID;

import com.wenghong.notifyflow.identity.domain.AppUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppUserDetailsTests {

    private static final UUID ID = UUID.randomUUID();
    private static final String HASH = "encoded-password-test-fixture";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesDatabaseIdentityAndEnabledFlagWithoutGlobalTenantAuthorities(boolean enabled) {
        AppUser user = user();
        ReflectionTestUtils.setField(user, "enabled", enabled);

        AppUserDetails details = new AppUserDetails(user);

        assertEquals(ID, details.getUserId());
        assertEquals("Alice@example.com", details.getUsername());
        assertEquals(HASH, details.getPassword());
        assertEquals(enabled, details.isEnabled());
        assertTrue(details.isAccountNonExpired());
        assertTrue(details.isCredentialsNonExpired());
        assertTrue(details.isAccountNonLocked());
        assertTrue(details.getAuthorities().isEmpty());
    }

    @Test
    void erasingPrincipalCredentialsDoesNotChangeDatabaseEntityHash() {
        AppUser user = user();
        AppUserDetails details = new AppUserDetails(user);

        details.eraseCredentials();

        assertNull(details.getPassword());
        assertEquals(HASH, user.getPasswordHash());
        assertEquals(ID, details.getUserId());
    }

    @Test
    void stringRepresentationDoesNotExposePasswordHash() {
        AppUserDetails details = new AppUserDetails(user());

        assertFalse(details.toString().contains(HASH));
    }

    private AppUser user() {
        AppUser user = new AppUser("Alice@example.com", HASH, Instant.parse("2026-11-20T00:00:00Z"));
        ReflectionTestUtils.setField(user, "id", ID);
        return user;
    }
}
