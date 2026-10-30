package com.wenghong.notifyflow.event.persistence;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.event.domain.Event;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
class EventRepositoryTests {

    private static final Instant CREATED_AT = Instant.parse("2026-10-29T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private EventRepository events;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void findsEventByIdempotencyKey() {
        JsonNode payload = JSON.readTree("{\"orderNumber\":\"128\"}");
        Event first = new Event("order.confirmed", payload, "request-1", CREATED_AT);
        Event second = new Event("order.confirmed", payload, "request-2", CREATED_AT);
        events.saveAllAndFlush(List.of(first, second));
        entityManager.clear();

        Event found = events.findByIdempotencyKey("request-2").orElseThrow();

        assertEquals(second.getId(), found.getId());
        assertEquals("request-2", found.getIdempotencyKey());
        assertEquals("order.confirmed", found.getEventType());
        assertEquals(payload, found.getPayload());
        assertEquals(CREATED_AT, found.getCreatedAt());
    }

    @Test
    void returnsEmptyForUnknownIdempotencyKey() {
        events.saveAndFlush(new Event(
                "order.confirmed", JSON.readTree("{}"), "existing-request", CREATED_AT));
        entityManager.clear();

        assertTrue(events.findByIdempotencyKey("unknown-request").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            """
            {
              "order": {"number": "128", "total": 12.5},
              "items": [{"sku": "A", "quantity": 2}],
              "confirmed": true,
              "note": null
            }
            """,
            """
            [{"sku":"A"}, [1, 2, 3], true, null, "notification"]
            """
    })
    void savesAndReloadsEventWithStructuredJsonPayload(String json) {
        JsonNode payload = JSON.readTree(json);
        Event saved = events.saveAndFlush(new Event(
                "order.confirmed", payload, "order-128-confirmed", CREATED_AT));
        UUID id = saved.getId();
        assertNotNull(id);
        entityManager.clear();

        Event reloaded = events.findById(id).orElseThrow();

        assertEquals(id, reloaded.getId());
        assertEquals("order.confirmed", reloaded.getEventType());
        assertEquals("order-128-confirmed", reloaded.getIdempotencyKey());
        assertEquals(CREATED_AT, reloaded.getCreatedAt());
        assertEquals(payload, reloaded.getPayload());
        // Check PostgreSQL stores structured JSON, not an escaped JSON string.
        assertEquals(payload.isArray() ? "array" : "object", jdbcTemplate.queryForObject(
                "SELECT jsonb_typeof(payload) FROM events WHERE id = ?", String.class, id));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsDuplicateIdempotencyKeyEvenWhenEventDataDiffers(boolean differentData) {
        JsonNode original = JSON.readTree("{\"orderNumber\":\"128\"}");
        events.saveAndFlush(new Event("order.confirmed", original, "request-128", CREATED_AT));
        String eventType = differentData ? "payment.failed" : "order.confirmed";
        JsonNode payload = differentData ? JSON.readTree("{\"reason\":\"declined\"}") : original;

        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> events.saveAndFlush(new Event(eventType, payload, "request-128", CREATED_AT)));

        assertSqlState(exception, "23505"); // unique_violation
    }

    @Test
    void allowsSameEventDataWithDifferentIdempotencyKeys() {
        JsonNode payload = JSON.readTree("{\"orderNumber\":\"128\"}");
        Event first = new Event("order.confirmed", payload, "request-1", CREATED_AT);
        Event second = new Event("order.confirmed", payload, "request-2", CREATED_AT);
        events.saveAllAndFlush(List.of(first, second));
        entityManager.clear();

        assertNotEquals(first.getId(), second.getId());
        assertEquals(2L, events.count());
        assertEquals("request-1", events.findById(first.getId()).orElseThrow().getIdempotencyKey());
        assertEquals("request-2", events.findById(second.getId()).orElseThrow().getIdempotencyKey());
    }

    @ParameterizedTest
    @ValueSource(strings = {"event_type", "payload", "idempotency_key", "created_at"})
    void databaseRejectsNullRequiredFields(String nullColumn) {
        String eventType = nullColumn.equals("event_type") ? null : "order.confirmed";
        String payload = nullColumn.equals("payload") ? null : "{\"orderNumber\":\"128\"}";
        String key = nullColumn.equals("idempotency_key") ? null : "request-128";
        Timestamp createdAt = nullColumn.equals("created_at") ? null : Timestamp.from(CREATED_AT);

        // Bypass ORM checks to verify the migration's database constraints directly.
        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                        INSERT INTO events (id, event_type, payload, idempotency_key, created_at)
                        VALUES (?, ?, CAST(? AS jsonb), ?, ?)
                        """, UUID.randomUUID(), eventType, payload, key, createdAt));

        assertSqlState(exception, "23502"); // not_null_violation
    }

    private void assertSqlState(DataIntegrityViolationException exception, String expected) {
        SQLException cause = assertInstanceOf(SQLException.class, exception.getMostSpecificCause());
        assertEquals(expected, cause.getSQLState());
    }
}
