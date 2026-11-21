package com.wenghong.notifyflow.delivery.messaging.outbox;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.event.domain.Event;
import com.wenghong.notifyflow.event.persistence.EventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeliveryOutboxRepositoryTests {

    @Autowired private TenantRepository tenants;
    private Tenant tenant;

    @BeforeEach
    void createFixtureTenant() {
        tenant = tenants.saveAndFlush(new Tenant("Fixture workspace", Instant.parse("2026-11-20T00:00:00Z")));
    }

    private static final String TEST_SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static final Instant CREATED_AT = Instant.parse("2026-11-10T00:00:00Z");
    private static final Instant PUBLISHED_AT = CREATED_AT.plusSeconds(2);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private DeliveryOutboxRepository outbox;
    @Autowired private DeliveryRepository deliveries;
    @Autowired private EventRepository events;
    @Autowired private WebhookEndpointRepository endpoints;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactions;

    @AfterEach
    void cleanUpCommittedRows() {
        // Each test commits into its isolated PostgreSQL container so worker transactions can see the fixtures.
        outbox.deleteAllInBatch();
        deliveries.deleteAllInBatch();
        events.deleteAllInBatch();
        endpoints.deleteAllInBatch();
        tenants.deleteAllInBatch();
    }

    @Test
    void savesAndReloadsUnpublishedMessage() {
        UUID deliveryId = saveDelivery().getId();
        DeliveryOutboxMessage saved = outbox.saveAndFlush(new DeliveryOutboxMessage(deliveryId, CREATED_AT));
        assertNotNull(saved.getId());

        // Repository calls run in separate transactions; this read reloads the committed row.
        DeliveryOutboxMessage reloaded = outbox.findById(saved.getId()).orElseThrow();

        assertEquals(saved.getId(), reloaded.getId());
        assertEquals(deliveryId, reloaded.getDeliveryId());
        assertEquals(CREATED_AT, reloaded.getCreatedAt());
        assertNull(reloaded.getPublishedAt());
    }

    @Test
    void publicationTimestampIsPersistedThroughDirtyChecking() {
        DeliveryOutboxMessage saved = saveMessage(CREATED_AT);

        transactions.executeWithoutResult(status ->
                outbox.findById(saved.getId()).orElseThrow().markPublished(PUBLISHED_AT));

        DeliveryOutboxMessage reloaded = outbox.findById(saved.getId()).orElseThrow();
        assertEquals(PUBLISHED_AT, reloaded.getPublishedAt());
        assertEquals(CREATED_AT, reloaded.getCreatedAt());
        assertEquals(saved.getDeliveryId(), reloaded.getDeliveryId());
    }

    @Test
    void allowsMultiplePublishingRequestsForSameDelivery() {
        UUID deliveryId = saveDelivery().getId();
        DeliveryOutboxMessage first = outbox.saveAndFlush(new DeliveryOutboxMessage(deliveryId, CREATED_AT));
        DeliveryOutboxMessage replay = outbox.saveAndFlush(
                new DeliveryOutboxMessage(deliveryId, CREATED_AT.plusSeconds(1)));

        assertNotEquals(first.getId(), replay.getId());
        assertEquals(deliveryId, outbox.findById(first.getId()).orElseThrow().getDeliveryId());
        assertEquals(deliveryId, outbox.findById(replay.getId()).orElseThrow().getDeliveryId());
        assertEquals(2L, outbox.count());
    }

    @Test
    void pendingLookupReturnsEmptyWhenOutboxIsEmpty() {
        assertTrue(findNext().isEmpty());
    }

    @Test
    void pendingLookupReturnsEmptyWhenAllMessagesArePublished() {
        DeliveryOutboxMessage saved = saveMessage(CREATED_AT);
        markPublished(saved.getId());

        assertTrue(findNext().isEmpty());
        assertEquals(1L, outbox.count());
    }

    @Test
    void pendingLookupExcludesPublishedRows() {
        UUID deliveryId = saveDelivery().getId();
        DeliveryOutboxMessage published = outbox.saveAndFlush(
                new DeliveryOutboxMessage(deliveryId, CREATED_AT));
        DeliveryOutboxMessage pending = outbox.saveAndFlush(
                new DeliveryOutboxMessage(deliveryId, CREATED_AT.plusSeconds(1)));
        markPublished(published.getId());

        assertEquals(pending.getId(), findNext().orElseThrow().getId());
    }

    @Test
    void pendingLookupOrdersByCreationTimeThenIdAndReturnsOneRow() {
        UUID deliveryId = saveDelivery().getId();
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID tiedId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID newestId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        // Insert in a different order to ensure insertion order cannot satisfy the assertions.
        insertMessage(newestId, deliveryId, Timestamp.from(CREATED_AT.plusSeconds(1)));
        insertMessage(tiedId, deliveryId, Timestamp.from(CREATED_AT));
        insertMessage(firstId, deliveryId, Timestamp.from(CREATED_AT));

        assertEquals(firstId, findNext().orElseThrow().getId());
        markPublished(firstId);
        assertEquals(tiedId, findNext().orElseThrow().getId());
        markPublished(tiedId);
        assertEquals(newestId, findNext().orElseThrow().getId());
    }

    @Test
    void rolledBackPublicationRemainsAvailableForAnotherAttempt() {
        DeliveryOutboxMessage saved = saveMessage(CREATED_AT);

        transactions.executeWithoutResult(status -> {
            DeliveryOutboxMessage locked = outbox.findNextUnpublishedForUpdate().orElseThrow();
            locked.markPublished(PUBLISHED_AT);
            outbox.flush();
            status.setRollbackOnly();
        });

        assertNull(outbox.findById(saved.getId()).orElseThrow().getPublishedAt());
        assertEquals(saved.getId(), findNext().orElseThrow().getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anotherTransactionSkipsLockedRowAndFindsNextAvailableOrEmpty(boolean anotherRowExists)
            throws Exception {
        UUID deliveryId = saveDelivery().getId();
        DeliveryOutboxMessage oldest = outbox.saveAndFlush(new DeliveryOutboxMessage(deliveryId, CREATED_AT));
        Optional<UUID> expected = anotherRowExists
                ? Optional.of(outbox.saveAndFlush(
                        new DeliveryOutboxMessage(deliveryId, CREATED_AT.plusSeconds(1))).getId())
                : Optional.empty();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstWorker = executor.submit(() -> transactions.execute(status -> {
                DeliveryOutboxMessage message = outbox.findNextUnpublishedForUpdate().orElseThrow();
                assertEquals(oldest.getId(), message.getId());
                locked.countDown();
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS), "First worker was not released");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return message.getId();
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "First worker did not acquire the row lock");

            var secondWorker = executor.submit(() -> transactions.execute(status ->
                    outbox.findNextUnpublishedForUpdate().map(DeliveryOutboxMessage::getId)));

            // A missing SKIP LOCKED would block this future until the first worker releases its lock.
            assertEquals(expected, secondWorker.get(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(oldest.getId(), firstWorker.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Workers must finish before cleanup");
        }

        // With the first transaction finished, its still-unpublished row is eligible again.
        assertEquals(oldest.getId(), findNext().orElseThrow().getId());
        assertNull(outbox.findById(oldest.getId()).orElseThrow().getPublishedAt());
    }

    @Test
    void databaseRejectsMissingDelivery() {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> outbox.saveAndFlush(new DeliveryOutboxMessage(UUID.randomUUID(), CREATED_AT)));

        assertSqlState(error, "23503");
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "delivery_id", "created_at"})
    void databaseRejectsNullRequiredFields(String column) {
        UUID deliveryId = saveDelivery().getId();
        UUID idValue = column.equals("id") ? null : UUID.randomUUID();
        UUID deliveryValue = column.equals("delivery_id") ? null : deliveryId;
        Timestamp createdValue = column.equals("created_at") ? null : Timestamp.from(CREATED_AT);

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> insertMessage(idValue, deliveryValue, createdValue));

        assertSqlState(error, "23502");
    }

    private Optional<DeliveryOutboxMessage> findNext() {
        return transactions.execute(status -> outbox.findNextUnpublishedForUpdate());
    }

    private void markPublished(UUID id) {
        transactions.executeWithoutResult(status ->
                outbox.findById(id).orElseThrow().markPublished(PUBLISHED_AT));
    }

    private DeliveryOutboxMessage saveMessage(Instant createdAt) {
        return outbox.saveAndFlush(new DeliveryOutboxMessage(saveDelivery().getId(), createdAt));
    }

    private Delivery saveDelivery() {
        Event event = events.saveAndFlush(new Event(tenant, "order.confirmed",
                JSON.readTree("{\"orderNumber\":\"128\"}"), UUID.randomUUID().toString(), CREATED_AT));
        WebhookEndpoint endpoint = endpoints.saveAndFlush(
                new WebhookEndpoint(tenant, "Warehouse", "https://example.com/webhooks", CREATED_AT, TEST_SIGNING_SECRET));
        return deliveries.saveAndFlush(new Delivery(event, endpoint, CREATED_AT));
    }

    private int insertMessage(UUID id, UUID deliveryId, Timestamp createdAt) {
        return jdbcTemplate.update("INSERT INTO delivery_outbox (id, delivery_id, created_at) VALUES (?, ?, ?)",
                id, deliveryId, createdAt);
    }

    private void assertSqlState(DataIntegrityViolationException error, String expected) {
        SQLException cause = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(expected, cause.getSQLState());
    }
}
