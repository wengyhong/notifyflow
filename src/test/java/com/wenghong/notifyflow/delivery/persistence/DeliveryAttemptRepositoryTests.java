package com.wenghong.notifyflow.delivery.persistence;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryAttempt;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.event.domain.Event;
import com.wenghong.notifyflow.event.persistence.EventRepository;
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
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class DeliveryAttemptRepositoryTests {

    private static final Instant STARTED_AT = Instant.parse("2026-11-02T00:00:00Z");
    private static final Instant COMPLETED_AT = STARTED_AT.plusSeconds(2);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private DeliveryAttemptRepository attempts;

    @Autowired
    private DeliveryRepository deliveries;

    @Autowired
    private EventRepository events;

    @Autowired
    private WebhookEndpointRepository endpoints;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void latestAttemptLookupIsEmptyWhenDeliveryHasNoHistory() {
        Delivery delivery = saveDelivery();

        assertTrue(attempts.findFirstByDelivery_IdOrderByAttemptNumberDesc(delivery.getId()).isEmpty());
        assertTrue(attempts.findFirstByDelivery_IdOrderByAttemptNumberDesc(UUID.randomUUID()).isEmpty());
    }

    @Test
    void latestAttemptLookupUsesHighestNumberForRequestedDelivery() {
        Delivery delivery = saveDelivery();
        Delivery other = saveDelivery();
        DeliveryAttempt highest = attempts.saveAndFlush(new DeliveryAttempt(delivery, 3, STARTED_AT));
        attempts.saveAndFlush(new DeliveryAttempt(delivery, 1, STARTED_AT.plusSeconds(20)));
        attempts.saveAndFlush(new DeliveryAttempt(delivery, 2, STARTED_AT.plusSeconds(10)));
        attempts.saveAndFlush(new DeliveryAttempt(other, 9, STARTED_AT.plusSeconds(30)));
        entityManager.clear();

        DeliveryAttempt found = attempts.findFirstByDelivery_IdOrderByAttemptNumberDesc(delivery.getId())
                .orElseThrow();

        assertEquals(highest.getId(), found.getId());
        assertEquals(3, found.getAttemptNumber());
        assertEquals(delivery.getId(), found.getDelivery().getId());
    }

    @Test
    void savesAndReloadsUnfinishedAttemptWithDeliveryRelationship() {
        Delivery delivery = saveDelivery();
        DeliveryAttempt saved = attempts.saveAndFlush(new DeliveryAttempt(delivery, 1, STARTED_AT));
        UUID id = saved.getId();
        assertNotNull(id);
        entityManager.clear();

        DeliveryAttempt reloaded = attempts.findById(id).orElseThrow();

        assertEquals(id, reloaded.getId());
        assertEquals(delivery.getId(), reloaded.getDelivery().getId());
        assertEquals(STARTED_AT, reloaded.getDelivery().getCreatedAt());
        assertEquals(1, reloaded.getAttemptNumber());
        assertEquals(STARTED_AT, reloaded.getStartedAt());
        assertNull(reloaded.getCompletedAt());
        assertNull(reloaded.getResponseStatus());
        assertNull(reloaded.getErrorMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 503})
    void persistsHttpResponseOutcomeThroughDirtyChecking(int status) {
        DeliveryAttempt attempt = attempts.saveAndFlush(
                new DeliveryAttempt(saveDelivery(), 1, STARTED_AT));

        attempt.completeWithResponse(status, COMPLETED_AT);
        attempts.flush();
        entityManager.clear();

        DeliveryAttempt reloaded = attempts.findById(attempt.getId()).orElseThrow();
        assertEquals(Integer.valueOf(status), reloaded.getResponseStatus());
        assertEquals(COMPLETED_AT, reloaded.getCompletedAt());
        assertEquals(STARTED_AT, reloaded.getStartedAt());
        assertNull(reloaded.getErrorMessage());
    }

    @Test
    void persistsNetworkFailureWithoutHttpResponseStatus() {
        DeliveryAttempt attempt = attempts.saveAndFlush(
                new DeliveryAttempt(saveDelivery(), 1, STARTED_AT));

        attempt.completeWithFailure("Connection timed out", COMPLETED_AT);
        attempts.flush();
        entityManager.clear();

        DeliveryAttempt reloaded = attempts.findById(attempt.getId()).orElseThrow();
        assertEquals("Connection timed out", reloaded.getErrorMessage());
        assertEquals(COMPLETED_AT, reloaded.getCompletedAt());
        assertEquals(STARTED_AT, reloaded.getStartedAt());
        assertNull(reloaded.getResponseStatus());
    }

    @Test
    void rejectsDuplicateAttemptNumberForSameDelivery() {
        Delivery delivery = saveDelivery();
        attempts.saveAndFlush(new DeliveryAttempt(delivery, 1, STARTED_AT));

        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> attempts.saveAndFlush(new DeliveryAttempt(delivery, 1, STARTED_AT)));

        assertSqlState(exception, "23505"); // unique_violation
    }

    @Test
    void allowsDifferentAttemptNumbersForSameDelivery() {
        Delivery delivery = saveDelivery();
        DeliveryAttempt first = new DeliveryAttempt(delivery, 1, STARTED_AT);
        first.completeWithFailure("Connection timed out", COMPLETED_AT);
        DeliveryAttempt second = new DeliveryAttempt(delivery, 2, COMPLETED_AT.plusSeconds(1));
        attempts.saveAllAndFlush(List.of(first, second));
        entityManager.clear();

        DeliveryAttempt firstReloaded = attempts.findById(first.getId()).orElseThrow();
        DeliveryAttempt secondReloaded = attempts.findById(second.getId()).orElseThrow();
        assertEquals(1, firstReloaded.getAttemptNumber());
        assertEquals(2, secondReloaded.getAttemptNumber());
        assertEquals(delivery.getId(), firstReloaded.getDelivery().getId());
        assertEquals(delivery.getId(), secondReloaded.getDelivery().getId());
        assertEquals(2L, attempts.count());
    }

    @Test
    void allowsSameAttemptNumberForDifferentDeliveries() {
        Delivery firstDelivery = saveDelivery();
        Delivery secondDelivery = saveDelivery();
        DeliveryAttempt first = new DeliveryAttempt(firstDelivery, 1, STARTED_AT);
        DeliveryAttempt second = new DeliveryAttempt(secondDelivery, 1, STARTED_AT);
        attempts.saveAllAndFlush(List.of(first, second));
        entityManager.clear();

        assertEquals(firstDelivery.getId(), attempts.findById(first.getId())
                .orElseThrow().getDelivery().getId());
        assertEquals(secondDelivery.getId(), attempts.findById(second.getId())
                .orElseThrow().getDelivery().getId());
        assertEquals(2L, attempts.count());
    }

    @Test
    void databaseRejectsMissingDelivery() {
        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAttempt(UUID.randomUUID(), 1, Timestamp.from(STARTED_AT)));

        assertSqlState(exception, "23503"); // foreign_key_violation
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void databaseRejectsNonPositiveAttemptNumbers(int attemptNumber) {
        UUID deliveryId = saveDelivery().getId();

        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAttempt(deliveryId, attemptNumber, Timestamp.from(STARTED_AT)));

        assertSqlState(exception, "23514"); // check_violation
    }

    @ParameterizedTest
    @ValueSource(strings = {"delivery_id", "attempt_number", "started_at"})
    void databaseRejectsNullRequiredFields(String nullColumn) {
        Delivery delivery = saveDelivery();
        UUID deliveryId = nullColumn.equals("delivery_id") ? null : delivery.getId();
        Integer attemptNumber = nullColumn.equals("attempt_number") ? null : 1;
        Timestamp startedAt = nullColumn.equals("started_at") ? null : Timestamp.from(STARTED_AT);

        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAttempt(deliveryId, attemptNumber, startedAt));

        assertSqlState(exception, "23502"); // not_null_violation
    }

    private Delivery saveDelivery() {
        Event event = events.saveAndFlush(new Event(
                "order.confirmed", JSON.readTree("{\"orderNumber\":\"128\"}"),
                UUID.randomUUID().toString(), STARTED_AT));
        WebhookEndpoint endpoint = endpoints.saveAndFlush(new WebhookEndpoint(
                "Warehouse", "https://example.com/webhooks", STARTED_AT));
        Delivery delivery = new Delivery(event, endpoint, STARTED_AT);
        delivery.tryStartProcessing();
        return deliveries.saveAndFlush(delivery);
    }

    private int insertAttempt(UUID deliveryId, Integer attemptNumber, Timestamp startedAt) {
        // Bypass constructor and ORM validation to exercise PostgreSQL's constraints.
        return jdbcTemplate.update("""
                INSERT INTO delivery_attempts (id, delivery_id, attempt_number, started_at)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), deliveryId, attemptNumber, startedAt);
    }

    private void assertSqlState(DataIntegrityViolationException exception, String expected) {
        SQLException cause = assertInstanceOf(SQLException.class, exception.getMostSpecificCause());
        assertEquals(expected, cause.getSQLState());
    }
}
