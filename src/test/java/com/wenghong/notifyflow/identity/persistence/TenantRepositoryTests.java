package com.wenghong.notifyflow.identity.persistence;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.identity.domain.Tenant;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class TenantRepositoryTests {

    private static final Instant CREATED = Instant.parse("2026-11-20T00:00:00.123456Z");

    @Autowired private TenantRepository tenants;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void persistsGeneratedIdAndReloadsTenantDetails() {
        Tenant saved = tenants.saveAndFlush(new Tenant("Company A", CREATED));
        UUID id = saved.getId();
        assertNotNull(id);
        entityManager.clear();

        Tenant found = tenants.findById(id).orElseThrow();

        assertEquals(id, found.getId());
        assertEquals("Company A", found.getName());
        assertEquals(CREATED, found.getCreatedAt());
    }

    @Test
    void distinctTenantsCanShareDisplayName() {
        List<Tenant> saved = tenants.saveAllAndFlush(List.of(
                new Tenant("Company", CREATED), new Tenant("Company", CREATED)));
        entityManager.clear();

        assertNotEquals(saved.get(0).getId(), saved.get(1).getId());
        assertTrue(tenants.findById(saved.get(0).getId()).isPresent());
        assertTrue(tenants.findById(saved.get(1).getId()).isPresent());
    }

    @Test
    void unknownIdReturnsEmpty() {
        tenants.saveAndFlush(new Tenant("Company A", CREATED));

        assertTrue(tenants.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void acceptsNameAtHundredCharacterLimit() {
        String name = "租".repeat(100);
        UUID id = tenants.saveAndFlush(new Tenant(name, CREATED)).getId();
        entityManager.clear();

        assertEquals(name, tenants.findById(id).orElseThrow().getName());
    }

    @Test
    void databaseRejectsNameBeyondLimit() {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, ?)",
                        UUID.randomUUID(), "x".repeat(101), Timestamp.from(CREATED)));

        assertSqlState(error, "22001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "created_at"})
    void databaseRequiresNameAndCreationTime(String missing) {
        String name = missing.equals("name") ? null : "Company A";
        Timestamp created = missing.equals("created_at") ? null : Timestamp.from(CREATED);

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, ?)",
                        UUID.randomUUID(), name, created));

        assertSqlState(error, "23502");
    }

    private void assertSqlState(DataIntegrityViolationException error, String expected) {
        SQLException cause = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(expected, cause.getSQLState());
    }
}
