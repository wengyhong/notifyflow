package com.wenghong.notifyflow.identity.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
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

class RequiredResourceTenantMigrationTests {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static final UUID LEGACY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CREATED = Instant.parse("2026-11-01T00:00:00Z");
    private static final String SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @BeforeAll static void startDatabase() { POSTGRES.start(); }
    @AfterAll static void stopDatabase() { POSTGRES.stop(); }

    @ParameterizedTest
    @CsvSource({
            "false,false,false,false",
            "false,false,true,false",
            "true,false,false,false",
            "false,true,false,false",
            "true,true,true,false",
            "true,true,true,true"
    })
    void backfillsUnassignedResourcesPreservesOwnersAndRequiresTenant(
            boolean unassignedEndpoint, boolean unassignedEvent,
            boolean ownedResources, boolean existingLegacy) throws Exception {
        // Flyway clean is restricted to this class's dedicated disposable database.
        Flyway baseline = migration("20");
        baseline.clean();
        baseline.migrate();
        UUID owner = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            if (ownedResources) insertTenant(connection, owner, "Company A");
            if (existingLegacy) insertTenant(connection, LEGACY, "Preserved legacy name");
            UUID ownedEndpointId = ownedResources ? insertResource(connection, false, owner) : null;
            UUID ownedEventId = ownedResources ? insertResource(connection, true, owner) : null;
            UUID unassignedEndpointId = unassignedEndpoint ? insertResource(connection, false, null) : null;
            UUID unassignedEventId = unassignedEvent ? insertResource(connection, true, null) : null;

            Flyway migration = migration("21");
            assertEquals(1, migration.migrate().migrationsExecuted);
            assertEquals(0, migration.migrate().migrationsExecuted);

            if (ownedResources) {
                assertPreservedResource(connection, false, ownedEndpointId, owner);
                assertPreservedResource(connection, true, ownedEventId, owner);
            }
            if (unassignedEndpoint) assertPreservedResource(connection, false, unassignedEndpointId, LEGACY);
            if (unassignedEvent) assertPreservedResource(connection, true, unassignedEventId, LEGACY);
            int expectedTenants = (ownedResources ? 1 : 0) + (unassignedEndpoint || unassignedEvent || existingLegacy ? 1 : 0);
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT COUNT(*) FROM tenants")) {
                rows.next();
                assertEquals(expectedTenants, rows.getInt(1));
            }
            if (unassignedEndpoint || unassignedEvent || existingLegacy) {
                try (var query = connection.prepareStatement("SELECT name FROM tenants WHERE id = ?")) {
                    query.setObject(1, LEGACY);
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(existingLegacy ? "Preserved legacy name" : "Legacy workspace", rows.getString(1));
                    }
                }
            }

            for (boolean event : new boolean[] {false, true}) {
                SQLException rejected = assertThrows(SQLException.class, () -> insertResource(connection, event, null));
                assertEquals("23502", rejected.getSQLState());
                assertTrue(rejected.getMessage().contains("tenant_id"));
                try (var query = connection.prepareStatement("""
                        SELECT is_nullable FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = ? AND column_name = 'tenant_id'
                        """)) {
                    query.setString(1, event ? "events" : "webhook_endpoints");
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("NO", rows.getString(1));
                    }
                }
            }
        }
    }

    private void insertTenant(Connection connection, UUID id, String name) throws Exception {
        try (var insert = connection.prepareStatement("INSERT INTO tenants VALUES (?, ?, CURRENT_TIMESTAMP)")) {
            insert.setObject(1, id);
            insert.setString(2, name);
            insert.executeUpdate();
        }
    }

    private UUID insertResource(Connection connection, boolean event, UUID tenantId) throws SQLException {
        UUID id = UUID.randomUUID();
        String sql = event ? """
                INSERT INTO events (id, tenant_id, event_type, payload, idempotency_key, created_at)
                VALUES (?, ?, 'order.confirmed', '{"orderId":128}'::jsonb, ?, '2026-11-01T00:00:00Z')
                """ : """
                INSERT INTO webhook_endpoints (id, tenant_id, name, url, signing_secret, created_at, updated_at)
                VALUES (?, ?, 'Orders', 'https://example.com/webhook', ?, '2026-11-01T00:00:00Z', '2026-11-01T00:00:00Z')
                """;
        try (var insert = connection.prepareStatement(sql)) {
            insert.setObject(1, id);
            insert.setObject(2, tenantId);
            insert.setString(3, event ? "key-" + id : SECRET);
            insert.executeUpdate();
        }
        return id;
    }

    private void assertPreservedResource(Connection connection, boolean event, UUID id, UUID owner) throws Exception {
        String table = event ? "events" : "webhook_endpoints";
        try (var query = connection.prepareStatement("SELECT * FROM " + table + " WHERE id = ?")) {
            query.setObject(1, id);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(owner, rows.getObject("tenant_id", UUID.class));
                assertEquals(CREATED, rows.getTimestamp("created_at").toInstant());
                if (event) {
                    assertEquals("order.confirmed", rows.getString("event_type"));
                    assertEquals("{\"orderId\": 128}", rows.getString("payload"));
                    assertEquals("key-" + id, rows.getString("idempotency_key"));
                } else {
                    assertEquals("Orders", rows.getString("name"));
                    assertEquals("https://example.com/webhook", rows.getString("url"));
                    assertEquals(SECRET, rows.getString("signing_secret"));
                    assertEquals(CREATED, rows.getTimestamp("updated_at").toInstant());
                }
            }
        }
    }

    private static Flyway migration(String version) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(version).cleanDisabled(false).load();
    }
}
