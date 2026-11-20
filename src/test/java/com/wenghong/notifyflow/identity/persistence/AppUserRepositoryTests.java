package com.wenghong.notifyflow.identity.persistence;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.identity.domain.AppUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class AppUserRepositoryTests {

    private static final Instant CREATED = Instant.parse("2026-11-20T00:00:00.123456Z");
    // Repository fixture only; password encoding belongs to the later account service.
    private static final String HASH = "encoded-password-test-fixture";

    @Autowired private AppUserRepository users;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void persistsGeneratedIdAndReloadsUserDetails() {
        UUID id = users.saveAndFlush(new AppUser("Alice@example.com", HASH, CREATED)).getId();
        assertNotNull(id);
        entityManager.clear();

        AppUser found = users.findById(id).orElseThrow();

        assertEquals(id, found.getId());
        assertEquals("Alice@example.com", found.getEmail());
        assertEquals(HASH, found.getPasswordHash());
        assertTrue(found.isEnabled());
        assertEquals(CREATED, found.getCreatedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice@example.com", "ALICE@EXAMPLE.COM", "alice@EXAMPLE.com"})
    void emailLookupIgnoresCaseAndReturnsCorrectUser(String email) {
        AppUser alice = users.saveAndFlush(new AppUser("Alice@example.com", HASH, CREATED));
        users.saveAndFlush(new AppUser("bob@example.com", HASH, CREATED));
        entityManager.clear();

        AppUser found = users.findByEmail(email).orElseThrow();

        assertEquals(alice.getId(), found.getId());
        assertEquals("Alice@example.com", found.getEmail());
    }

    @Test
    void unknownEmailReturnsEmpty() {
        users.saveAndFlush(new AppUser("alice@example.com", HASH, CREATED));
        entityManager.clear();

        assertTrue(users.findByEmail("missing@example.com").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Alice@example.com", "alice@example.com", "ALICE@EXAMPLE.COM"})
    void uniqueIndexRejectsSameEmailRegardlessOfCase(String duplicate) {
        users.saveAndFlush(new AppUser("Alice@example.com", HASH, CREATED));

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> users.saveAndFlush(new AppUser(duplicate, HASH, CREATED)));

        assertSqlState(error, "23505");
    }

    @Test
    void distinctEmailsCreateDistinctUsers() {
        List<AppUser> saved = users.saveAllAndFlush(List.of(
                new AppUser("alice@example.com", HASH, CREATED),
                new AppUser("alice@another.example", HASH, CREATED)));
        entityManager.clear();

        assertNotEquals(saved.get(0).getId(), saved.get(1).getId());
        assertEquals(saved.get(0).getId(), users.findByEmail("alice@example.com").orElseThrow().getId());
        assertEquals(saved.get(1).getId(), users.findByEmail("alice@another.example").orElseThrow().getId());
    }

    @Test
    void disabledUserCanBeLoadedWithDisabledFlagPreserved() {
        UUID id = users.saveAndFlush(new AppUser("alice@example.com", HASH, CREATED)).getId();
        jdbc.update("UPDATE app_users SET enabled = false WHERE id = ?", id);
        entityManager.clear();

        AppUser found = users.findByEmail("ALICE@example.com").orElseThrow();

        assertEquals(id, found.getId());
        assertFalse(found.isEnabled());
        // Authentication will enforce this flag; repository lookup itself does not authenticate.
    }

    @Test
    void databaseDefaultsEnabledToTrueWhenColumnIsOmitted() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, created_at) VALUES (?, ?, ?, ?)",
                id, "alice@example.com", HASH, Timestamp.from(CREATED));

        assertTrue(users.findById(id).orElseThrow().isEnabled());
    }

    @Test
    void acceptsEmailAndHashAtDeclaredLengthLimits() {
        String email = "a".repeat(242) + "@example.com";
        String hash = "h".repeat(255);
        UUID id = users.saveAndFlush(new AppUser(email, hash, CREATED)).getId();
        entityManager.clear();

        AppUser found = users.findById(id).orElseThrow();
        assertEquals(email, found.getEmail());
        assertEquals(hash, found.getPasswordHash());
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "password_hash"})
    void databaseRejectsValuesBeyondDeclaredLengthLimits(String column) {
        String email = column.equals("email") ? "a".repeat(255) : "alice@example.com";
        String hash = column.equals("password_hash") ? "h".repeat(256) : HASH;

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO app_users (id, email, password_hash, created_at) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(), email, hash, Timestamp.from(CREATED)));

        assertSqlState(error, "22001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "password_hash", "enabled", "created_at"})
    void databaseRequiresUserFields(String missing) {
        String email = missing.equals("email") ? null : "alice@example.com";
        String hash = missing.equals("password_hash") ? null : HASH;
        Boolean enabled = missing.equals("enabled") ? null : Boolean.TRUE;
        Timestamp created = missing.equals("created_at") ? null : Timestamp.from(CREATED);

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO app_users (id, email, password_hash, enabled, created_at) VALUES (?, ?, ?, ?, ?)",
                        UUID.randomUUID(), email, hash, enabled, created));

        assertSqlState(error, "23502");
    }

    private void assertSqlState(DataIntegrityViolationException error, String expected) {
        SQLException cause = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(expected, cause.getSQLState());
    }
}
