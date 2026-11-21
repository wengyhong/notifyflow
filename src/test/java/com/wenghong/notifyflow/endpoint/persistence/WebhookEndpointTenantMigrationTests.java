package com.wenghong.notifyflow.endpoint.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookEndpointTenantMigrationTests {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static final UUID LEGACY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @BeforeAll
    static void startDatabase() { POSTGRES.start(); }

    @AfterAll
    static void stopDatabase() { POSTGRES.stop(); }

    @BeforeEach
    void migrateToPreviousVersion() {
        // This is a disposable, dedicated Testcontainers database, never the development database.
        Flyway baseline = migration("16");
        baseline.clean();
        baseline.migrate();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void legacyWorkspaceIsCreatedOnlyWhenExistingResourcesNeedIt(boolean endpoints, boolean events) throws Exception {
        try (Connection connection = connection()) {
            UUID endpointId = endpoints ? insertEndpoint(connection) : null;
            if (events) insertEvent(connection);

            Flyway migration = migration("18");
            assertEquals(2, migration.migrate().migrationsExecuted);
            assertEquals(endpoints || events ? 1 : 0, count(connection, "tenants"));
            assertEquals(events ? 1 : 0, count(connection, "events"));
            assertEquals(0, count(connection, "tenant_memberships"));
            if (endpoints || events) {
                try (var query = connection.prepareStatement("SELECT name FROM tenants WHERE id = ?")) {
                    query.setObject(1, LEGACY);
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("Legacy workspace", rows.getString(1));
                    }
                }
            }
            if (endpoints) assertEndpointPreserved(connection, endpointId);
            assertEquals(0, migration.migrate().migrationsExecuted);
            assertEquals(endpoints || events ? 1 : 0, count(connection, "tenants"));
            if (endpoints) assertEndpointPreserved(connection, endpointId);
        }
    }

    @Test
    void existingMembershipDoesNotGrantAccessToLegacyWorkspace() throws Exception {
        try (Connection connection = connection(); var sql = connection.createStatement()) {
            UUID tenantId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            UUID membershipId = UUID.randomUUID();
            try (var insert = connection.prepareStatement("INSERT INTO tenants VALUES (?, 'Company A', CURRENT_TIMESTAMP)")) {
                insert.setObject(1, tenantId);
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO app_users (id, email, password_hash, enabled, created_at)
                    VALUES (?, 'admin@example.com', 'preserved-hash', true, CURRENT_TIMESTAMP)
                    """)) {
                insert.setObject(1, userId);
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO tenant_memberships (id, tenant_id, user_id, role, created_at)
                    VALUES (?, ?, ?, 'ADMIN', CURRENT_TIMESTAMP)
                    """)) {
                insert.setObject(1, membershipId);
                insert.setObject(2, tenantId);
                insert.setObject(3, userId);
                insert.executeUpdate();
            }
            UUID endpointId = insertEndpoint(connection);
            migration("18").migrate();
            assertEndpointPreserved(connection, endpointId);
            assertEquals(2, count(connection, "tenants"));
            assertEquals(1, count(connection, "tenant_memberships"));
            try (var rows = sql.executeQuery("SELECT * FROM tenant_memberships")) {
                assertTrue(rows.next());
                assertEquals(membershipId, rows.getObject("id", UUID.class));
                assertEquals(tenantId, rows.getObject("tenant_id", UUID.class));
                assertEquals(userId, rows.getObject("user_id", UUID.class));
                assertEquals("ADMIN", rows.getString("role"));
            }
            try (var rows = sql.executeQuery("SELECT password_hash FROM app_users")) {
                assertTrue(rows.next());
                assertEquals("preserved-hash", rows.getString(1));
            }
            try (var rows = sql.executeQuery("""
                    SELECT COUNT(*) FROM pg_indexes
                    WHERE schemaname = 'public' AND indexname = 'idx_webhook_endpoints_tenant_id'
                    """)) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    private static Flyway migration(String version) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(version).cleanDisabled(false).load();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private UUID insertEndpoint(Connection connection) throws Exception {
        UUID id = UUID.randomUUID();
        try (var insert = connection.prepareStatement("""
                INSERT INTO webhook_endpoints (id, name, url, signing_secret, created_at, updated_at)
                VALUES (?, 'Orders', 'https://example.com/webhook', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)) {
            insert.setObject(1, id);
            insert.setString(2, SECRET);
            insert.executeUpdate();
        }
        return id;
    }

    private void insertEvent(Connection connection) throws Exception {
        try (var insert = connection.prepareStatement("""
                INSERT INTO events (id, event_type, payload, idempotency_key, created_at)
                VALUES (?, 'order.created', '{"orderId":128}'::jsonb, 'legacy-key', CURRENT_TIMESTAMP)
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.executeUpdate();
        }
    }

    private void assertEndpointPreserved(Connection connection, UUID id) throws Exception {
        try (var query = connection.prepareStatement("SELECT * FROM webhook_endpoints WHERE id = ?")) {
            query.setObject(1, id);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(LEGACY, rows.getObject("tenant_id", UUID.class));
                assertEquals("Orders", rows.getString("name"));
                assertEquals("https://example.com/webhook", rows.getString("url"));
                assertEquals(SECRET, rows.getString("signing_secret"));
            }
        }
    }

    private int count(Connection connection, String table) throws Exception {
        try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
