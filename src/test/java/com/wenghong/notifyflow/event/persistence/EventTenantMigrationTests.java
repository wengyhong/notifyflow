package com.wenghong.notifyflow.event.persistence;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTenantMigrationTests {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static final UUID LEGACY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    @BeforeAll static void startDatabase() { POSTGRES.start(); }
    @AfterAll static void stopDatabase() { POSTGRES.stop(); }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void migratesFreshOrLegacyEventsAndPreservesExistingLegacyWorkspace(boolean hasEvents, boolean hasTenant) throws Exception {
        // Clean only this class's dedicated disposable test database.
        Flyway baseline = migration("18");
        baseline.clean();
        baseline.migrate();
        UUID eventId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var sql = connection.createStatement()) {
            if (hasTenant) {
                try (var insert = connection.prepareStatement("INSERT INTO tenants VALUES (?, 'Existing legacy name', CURRENT_TIMESTAMP)")) {
                    insert.setObject(1, LEGACY);
                    insert.executeUpdate();
                }
            }
            if (hasEvents) {
                try (var insert = connection.prepareStatement("""
                        INSERT INTO events (id, event_type, payload, idempotency_key, created_at)
                        VALUES (?, 'order.confirmed', '{"orderId":128}'::jsonb, 'legacy-key', '2026-11-01T00:00:00Z')
                        """)) {
                    insert.setObject(1, eventId);
                    insert.executeUpdate();
                }
            }

            Flyway migration = migration("20");
            assertEquals(2, migration.migrate().migrationsExecuted);
            assertEquals(0, migration.migrate().migrationsExecuted);
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM tenants")) {
                rows.next();
                assertEquals(hasEvents ? 1 : 0, rows.getInt(1));
            }
            if (hasEvents) {
                try (var rows = sql.executeQuery("SELECT e.*, t.name AS tenant_name FROM events e JOIN tenants t ON t.id = e.tenant_id")) {
                    assertTrue(rows.next());
                    assertEquals(eventId, rows.getObject("id", UUID.class));
                    assertEquals(LEGACY, rows.getObject("tenant_id", UUID.class));
                    assertEquals("order.confirmed", rows.getString("event_type"));
                    assertEquals("legacy-key", rows.getString("idempotency_key"));
                    assertEquals("{\"orderId\": 128}", rows.getString("payload"));
                    assertEquals(java.time.Instant.parse("2026-11-01T00:00:00Z"), rows.getTimestamp("created_at").toInstant());
                    assertEquals(hasTenant ? "Existing legacy name" : "Legacy workspace", rows.getString("tenant_name"));
                }
            }

            // The old global constraint is gone; an owned duplicate still fails under the new constraint.
            assertEquals(0, scalar(sql, "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'uq_events_idempotency_key'"));
            assertEquals(1, scalar(sql, "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'uq_events_tenant_idempotency_key'"));
            assertEquals(1, scalar(sql, "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_events_tenant_id'"));
            if (hasEvents) {
                SQLException duplicate = assertThrows(SQLException.class, () -> sql.executeUpdate("""
                        INSERT INTO events (id, tenant_id, event_type, payload, idempotency_key, created_at)
                        SELECT gen_random_uuid(), tenant_id, event_type, payload, idempotency_key, created_at FROM events
                        """));
                assertEquals("23505", duplicate.getSQLState());
                assertTrue(duplicate.getMessage().contains("uq_events_tenant_idempotency_key"));
            }
        }
    }

    private int scalar(java.sql.Statement statement, String query) throws Exception {
        try (var rows = statement.executeQuery(query)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static Flyway migration(String version) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(version).cleanDisabled(false).load();
    }
}
