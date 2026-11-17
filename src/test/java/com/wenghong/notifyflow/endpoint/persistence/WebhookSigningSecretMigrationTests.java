package com.wenghong.notifyflow.endpoint.persistence;

import java.sql.DriverManager;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WebhookSigningSecretMigrationTests {

    @Test
    void backfillsMissingSecretsAndPreservesExistingSecrets() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .target("9")
                    .load()
                    .migrate();

            UUID firstId = UUID.randomUUID();
            UUID secondId = UUID.randomUUID();
            UUID existingId = UUID.randomUUID();
            String existingSecret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                try (var insert = connection.prepareStatement("""
                        INSERT INTO webhook_endpoints
                            (id, name, url, created_at, updated_at, signing_secret)
                        VALUES (?, 'Orders', 'https://example.com/webhook',
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                        """)) {
                    for (UUID id : new UUID[] {firstId, secondId, existingId}) {
                        insert.setObject(1, id);
                        insert.setString(2, id.equals(existingId) ? existingSecret : null);
                        insert.executeUpdate();
                    }
                }

                Flyway migration = Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .locations("classpath:db/migration")
                        .target("10")
                        .load();
                assertEquals(1, migration.migrate().migrationsExecuted);

                Map<UUID, String> secrets = readSecrets(connection);
                assertEquals(3, secrets.size());
                assertValidSecret(secrets.get(firstId));
                assertValidSecret(secrets.get(secondId));
                assertNotEquals(secrets.get(firstId), secrets.get(secondId));
                assertEquals(existingSecret, secrets.get(existingId));

                // Flyway must not regenerate secrets on a subsequent application startup.
                assertEquals(0, migration.migrate().migrationsExecuted);
                assertEquals(secrets, readSecrets(connection));
            }
        }
    }

    private Map<UUID, String> readSecrets(java.sql.Connection connection) throws Exception {
        Map<UUID, String> secrets = new HashMap<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT id, signing_secret FROM webhook_endpoints")) {
            while (rows.next()) {
                secrets.put(rows.getObject("id", UUID.class), rows.getString("signing_secret"));
            }
        }
        return secrets;
    }

    private void assertValidSecret(String secret) {
        assertNotNull(secret);
        assertEquals(44, secret.length());
        assertEquals(32, Base64.getDecoder().decode(secret).length);
    }
}
