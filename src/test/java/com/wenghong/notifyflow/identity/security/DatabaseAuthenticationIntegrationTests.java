package com.wenghong.notifyflow.identity.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.identity.configuration.PasswordConfiguration;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.persistence.AppUserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, PasswordConfiguration.class, DatabaseUserDetailsService.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class DatabaseAuthenticationIntegrationTests {

    private static final String PASSWORD = "NotifyFlow-test-password-128";
    private static final Instant CREATED = Instant.parse("2026-11-20T00:00:00Z");

    @Autowired private AppUserRepository users;
    @Autowired private DatabaseUserDetailsService userDetails;
    @Autowired private PasswordEncoder encoder;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    private ProviderManager authentication;

    @BeforeEach
    void configureTestAuthenticationProvider() {
        // Exercise the real provider and encoder without defining HTTP login configuration yet.
        var provider = new DaoAuthenticationProvider(userDetails);
        provider.setPasswordEncoder(encoder);
        authentication = new ProviderManager(provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice@example.com", "ALICE@EXAMPLE.COM", "alice@EXAMPLE.com"})
    void authenticatesStoredHashThroughCaseInsensitiveLookup(String email) {
        String storedHash = encoder.encode(PASSWORD);
        UUID id = users.saveAndFlush(new AppUser("Alice@example.com", storedHash, CREATED)).getId();
        entityManager.clear();

        Authentication result = authenticate(email, PASSWORD);

        AppUserDetails principal = assertInstanceOf(AppUserDetails.class, result.getPrincipal());
        assertTrue(result.isAuthenticated());
        assertEquals(id, principal.getUserId());
        assertEquals("Alice@example.com", result.getName());
        assertTrue(principal.getAuthorities().isEmpty());
        assertEquals(List.of(FactorGrantedAuthority.PASSWORD_AUTHORITY),
                result.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
        assertNull(result.getCredentials());
        assertNull(principal.getPassword());
        assertEquals(storedHash, users.findById(id).orElseThrow().getPasswordHash());
    }

    @Test
    void incorrectPasswordIsRejectedWithoutChangingStoredHash() {
        String storedHash = encoder.encode(PASSWORD);
        UUID id = users.saveAndFlush(new AppUser("Alice@example.com", storedHash, CREATED)).getId();
        entityManager.clear();

        assertThrows(BadCredentialsException.class, () -> authenticate("alice@example.com", "wrong-password"));

        assertEquals(storedHash, users.findById(id).orElseThrow().getPasswordHash());
    }

    @Test
    void unknownEmailProducesBadCredentialsRatherThanLeakingLookupException() {
        assertThrows(BadCredentialsException.class, () -> authenticate("missing@example.com", PASSWORD));
    }

    @Test
    void disabledDatabaseUserCannotAuthenticateEvenWithCorrectPassword() {
        UUID id = users.saveAndFlush(new AppUser("Alice@example.com", encoder.encode(PASSWORD), CREATED)).getId();
        jdbc.update("UPDATE app_users SET enabled = false WHERE id = ?", id);
        entityManager.clear();

        assertThrows(DisabledException.class, () -> authenticate("alice@example.com", PASSWORD));
    }

    private Authentication authenticate(String email, String password) {
        return authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
    }
}
