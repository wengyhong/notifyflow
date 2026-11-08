package com.wenghong.notifyflow.delivery.persistence;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.event.domain.Event;
import com.wenghong.notifyflow.event.persistence.EventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class DeliveryRepositoryTests {

    private static final Instant CREATED_AT = Instant.parse("2026-10-29T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    void dueRetryQueryOrdersByTimeThenIdAndHonorsBatchLimit() {
        Instant now = CREATED_AT.plusSeconds(120);
        Delivery latest = saveScheduledDelivery("latest", now);
        Delivery tieA = saveScheduledDelivery("tie-a", now.minusSeconds(30));
        Delivery earliest = saveScheduledDelivery("earliest", now.minusSeconds(60));
        Delivery tieB = saveScheduledDelivery("tie-b", now.minusSeconds(30));
        List<UUID> tiedIds = List.of(tieA.getId(), tieB.getId()).stream()
                // PostgreSQL compares UUID bytes unsigned; canonical text has the same ordering.
                .sorted(Comparator.comparing(UUID::toString)).toList();
        entityManager.clear();

        assertEquals(List.of(earliest.getId(), tiedIds.getFirst()), deliveries.findDueRetryIds(
                DeliveryStatus.RETRY_SCHEDULED, now, PageRequest.of(0, 2)));
        assertEquals(List.of(tiedIds.getLast(), latest.getId()), deliveries.findDueRetryIds(
                DeliveryStatus.RETRY_SCHEDULED, now, PageRequest.of(1, 2)));
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"PENDING", "PROCESSING", "DELIVERED", "FAILED"})
    void dueRetryQueryExcludesOtherStatusesEvenWithPastTimestamp(DeliveryStatus status) {
        Delivery delivery = new Delivery(saveEvent("not-scheduled"), saveEndpoint("Warehouse"), CREATED_AT);
        if (status != DeliveryStatus.PENDING) {
            delivery.tryStartProcessing();
        }
        if (status == DeliveryStatus.DELIVERED) {
            delivery.markDelivered();
        } else if (status == DeliveryStatus.FAILED) {
            delivery.markFailed();
        }
        delivery = deliveries.saveAndFlush(delivery);
        // Deliberately populate the timestamp to test the status filter independently.
        jdbcTemplate.update("UPDATE deliveries SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(CREATED_AT), delivery.getId());
        entityManager.clear();

        assertEquals(List.of(), deliveries.findDueRetryIds(
                DeliveryStatus.RETRY_SCHEDULED, CREATED_AT, PageRequest.of(0, 100)));
    }

    @Test
    void dueRetryQueryReturnsEmptyWhenNoDeliveriesExist() {
        assertEquals(List.of(), deliveries.findDueRetryIds(
                DeliveryStatus.RETRY_SCHEDULED, CREATED_AT, PageRequest.of(0, 100)));
    }

    @Test
    void findsDueRetryButExcludesFutureRetry() {
        WebhookEndpoint endpoint = saveEndpoint("Warehouse");

        Delivery due = new Delivery(saveEvent("due-retry"), endpoint, CREATED_AT);
        due.tryStartProcessing();
        due.scheduleRetry(CREATED_AT);

        Delivery future = new Delivery(saveEvent("future-retry"), endpoint, CREATED_AT);
        future.tryStartProcessing();
        future.scheduleRetry(CREATED_AT.plusSeconds(60));

        deliveries.saveAllAndFlush(List.of(due, future));
        entityManager.clear();

        List<UUID> result = deliveries.findDueRetryIds(
                DeliveryStatus.RETRY_SCHEDULED, CREATED_AT, PageRequest.of(0, 100));

        assertEquals(List.of(due.getId()), result);
        Delivery reloaded = deliveries.findById(due.getId()).orElseThrow();
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, reloaded.getStatus());
        assertEquals(CREATED_AT, reloaded.getNextAttemptAt());
    }

    @Test
    void savesAndReloadsPendingDeliveryWithEventAndEndpoint() {
        Event event = saveEvent("request-128");
        WebhookEndpoint endpoint = saveEndpoint("Warehouse");
        UUID eventId = event.getId();
        UUID endpointId = endpoint.getId();
        Delivery saved = deliveries.saveAndFlush(new Delivery(event, endpoint, CREATED_AT));
        UUID deliveryId = saved.getId();
        assertNotNull(deliveryId);
        entityManager.clear();

        Delivery reloaded = deliveries.findById(deliveryId).orElseThrow();

        assertEquals(deliveryId, reloaded.getId());
        assertEquals(eventId, reloaded.getEvent().getId());
        assertEquals(endpointId, reloaded.getEndpoint().getId());
        assertEquals("order.confirmed", reloaded.getEvent().getEventType());
        assertEquals("Warehouse", reloaded.getEndpoint().getName());
        assertEquals(DeliveryStatus.PENDING, reloaded.getStatus());
        assertEquals(CREATED_AT, reloaded.getCreatedAt());
        // Verify the enum is stored by name, not by its numeric position.
        assertEquals("PENDING", jdbcTemplate.queryForObject(
                "SELECT status FROM deliveries WHERE id = ?", String.class, deliveryId));
    }

    @Test
    void rejectsDuplicateDeliveryForSameEventAndEndpoint() {
        Event event = saveEvent("request-128");
        WebhookEndpoint endpoint = saveEndpoint("Warehouse");
        deliveries.saveAndFlush(new Delivery(event, endpoint, CREATED_AT));

        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> deliveries.saveAndFlush(new Delivery(event, endpoint, CREATED_AT)));

        assertSqlState(exception, "23505"); // unique_violation
    }

    @Test
    void allowsSameEventToBeDeliveredToDifferentEndpoints() {
        Event event = saveEvent("request-128");
        WebhookEndpoint warehouse = saveEndpoint("Warehouse");
        WebhookEndpoint email = saveEndpoint("Email");
        Delivery first = new Delivery(event, warehouse, CREATED_AT);
        Delivery second = new Delivery(event, email, CREATED_AT);
        deliveries.saveAllAndFlush(List.of(first, second));
        entityManager.clear();

        Delivery firstReloaded = deliveries.findById(first.getId()).orElseThrow();
        Delivery secondReloaded = deliveries.findById(second.getId()).orElseThrow();
        assertEquals(event.getId(), firstReloaded.getEvent().getId());
        assertEquals(event.getId(), secondReloaded.getEvent().getId());
        assertEquals(warehouse.getId(), firstReloaded.getEndpoint().getId());
        assertEquals(email.getId(), secondReloaded.getEndpoint().getId());
        assertEquals(2L, deliveries.count());
    }

    @Test
    void allowsDifferentEventsToBeDeliveredToSameEndpoint() {
        Event firstEvent = saveEvent("request-1");
        Event secondEvent = saveEvent("request-2");
        WebhookEndpoint endpoint = saveEndpoint("Warehouse");
        Delivery first = new Delivery(firstEvent, endpoint, CREATED_AT);
        Delivery second = new Delivery(secondEvent, endpoint, CREATED_AT);
        deliveries.saveAllAndFlush(List.of(first, second));
        entityManager.clear();

        Delivery firstReloaded = deliveries.findById(first.getId()).orElseThrow();
        Delivery secondReloaded = deliveries.findById(second.getId()).orElseThrow();
        assertEquals(firstEvent.getId(), firstReloaded.getEvent().getId());
        assertEquals(secondEvent.getId(), secondReloaded.getEvent().getId());
        assertEquals(endpoint.getId(), firstReloaded.getEndpoint().getId());
        assertEquals(endpoint.getId(), secondReloaded.getEndpoint().getId());
        assertEquals(2L, deliveries.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"event_id", "endpoint_id"})
    void databaseRejectsMissingEventOrEndpoint(String missingReference) {
        Event event = saveEvent("request-128");
        WebhookEndpoint endpoint = saveEndpoint("Warehouse");
        UUID eventId = missingReference.equals("event_id") ? UUID.randomUUID() : event.getId();
        UUID endpointId = missingReference.equals("endpoint_id") ? UUID.randomUUID() : endpoint.getId();

        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> insertDelivery(eventId, endpointId, "PENDING", Timestamp.from(CREATED_AT)));

        assertSqlState(exception, "23503"); // foreign_key_violation
    }

    @ParameterizedTest
    @ValueSource(strings = {"event_id", "endpoint_id", "status", "created_at"})
    void databaseRejectsNullRequiredFields(String nullColumn) {
        Event event = saveEvent("request-128");
        WebhookEndpoint endpoint = saveEndpoint("Warehouse");
        UUID eventId = nullColumn.equals("event_id") ? null : event.getId();
        UUID endpointId = nullColumn.equals("endpoint_id") ? null : endpoint.getId();
        String status = nullColumn.equals("status") ? null : "PENDING";
        Timestamp createdAt = nullColumn.equals("created_at") ? null : Timestamp.from(CREATED_AT);

        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> insertDelivery(eventId, endpointId, status, createdAt));

        assertSqlState(exception, "23502"); // not_null_violation
    }

    private Delivery saveScheduledDelivery(String key, Instant retryAt) {
        Delivery delivery = new Delivery(saveEvent(key), saveEndpoint(key), CREATED_AT);
        delivery.tryStartProcessing();
        delivery.scheduleRetry(retryAt);
        return deliveries.saveAndFlush(delivery);
    }

    private Event saveEvent(String key) {
        return events.saveAndFlush(new Event(
                "order.confirmed", JSON.readTree("{\"orderNumber\":\"128\"}"), key, CREATED_AT));
    }

    private WebhookEndpoint saveEndpoint(String name) {
        return endpoints.saveAndFlush(new WebhookEndpoint(
                name, "https://example.com/webhooks", CREATED_AT));
    }

    private int insertDelivery(UUID eventId, UUID endpointId, String status, Timestamp createdAt) {
        // Bypass ORM checks to exercise PostgreSQL's constraints directly.
        return jdbcTemplate.update("""
                INSERT INTO deliveries (id, event_id, endpoint_id, status, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), eventId, endpointId, status, createdAt);
    }

    private void assertSqlState(DataIntegrityViolationException exception, String expected) {
        SQLException cause = assertInstanceOf(SQLException.class, exception.getMostSpecificCause());
        assertEquals(expected, cause.getSQLState());
    }
}
