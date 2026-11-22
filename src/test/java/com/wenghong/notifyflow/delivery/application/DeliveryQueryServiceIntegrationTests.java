package com.wenghong.notifyflow.delivery.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryAttempt;
import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;
import com.wenghong.notifyflow.delivery.persistence.DeliveryAttemptRepository;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.event.domain.Event;
import com.wenghong.notifyflow.event.persistence.EventRepository;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, DeliveryQueryService.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class DeliveryQueryServiceIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    private static final String SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    @Autowired private DeliveryQueryService service;
    @Autowired private DeliveryRepository deliveries;
    @Autowired private DeliveryAttemptRepository attempts;
    @Autowired private EventRepository events;
    @Autowired private WebhookEndpointRepository endpoints;
    @Autowired private TenantRepository tenants;
    @Autowired private EntityManager entityManager;
    private Tenant owner;
    private WebhookEndpoint endpoint;
    private Delivery own;
    private List<Delivery> excluded;

    @BeforeEach
    void createTenantBoundaries() {
        owner = tenants.saveAndFlush(new Tenant("Owner", NOW));
        Tenant other = tenants.saveAndFlush(new Tenant("Other", NOW));
        endpoint = endpoints.saveAndFlush(new WebhookEndpoint(owner, "Owner receiver", "https://example.com/a", NOW, SECRET));
        WebhookEndpoint foreignEndpoint = endpoints.saveAndFlush(new WebhookEndpoint(other, "Other receiver", "https://example.com/b", NOW, SECRET));
        Event event = event(owner);
        Event foreignEvent = event(other);
        own = deliveries.saveAndFlush(new Delivery(event, endpoint, NOW));
        excluded = List.of(
                deliveries.saveAndFlush(new Delivery(foreignEvent, foreignEndpoint, NOW)),
                deliveries.saveAndFlush(new Delivery(event, foreignEndpoint, NOW)),
                deliveries.saveAndFlush(new Delivery(foreignEvent, endpoint, NOW)));
    }

    @ParameterizedTest
    @EnumSource(TenantRole.class)
    void bothRolesCanReadOwnedDeliveryButNeitherSideOfTenantBoundaryMayLeak(TenantRole role) {
        var access = new TenantAccess(owner.getId(), role);
        for (Delivery hidden : excluded) attempts.saveAndFlush(new DeliveryAttempt(hidden, 1, NOW));
        entityManager.clear();

        var list = service.findAll(access, null, PageRequest.of(0, 20));
        assertEquals(List.of(own.getId()), list.getContent().stream().map(response -> response.id()).toList());
        var detail = service.findById(access, own.getId()).orElseThrow();
        assertEquals(own.getEvent().getId(), detail.eventId());
        assertEquals(endpoint.getId(), detail.endpointId());
        assertEquals(DeliveryStatus.PENDING, detail.status());
        assertEquals(NOW, detail.createdAt());
        assertNull(detail.nextAttemptAt());
        assertEquals(1, detail.retryCycleStartAttempt());
        assertEquals(0, service.findAttempts(access, own.getId(), PageRequest.of(0, 20)).orElseThrow().getTotalElements());

        for (UUID hiddenId : List.of(excluded.get(0).getId(), excluded.get(1).getId(), excluded.get(2).getId(), UUID.randomUUID())) {
            assertTrue(service.findById(access, hiddenId).isEmpty());
            assertTrue(service.findAttempts(access, hiddenId, PageRequest.of(0, 20)).isEmpty());
            assertTrue(attempts.findHistoryByDeliveryIdAndTenantId(hiddenId, owner.getId(), PageRequest.of(0, 20)).isEmpty());
        }
        assertTrue(service.findAll(new TenantAccess(UUID.randomUUID(), role), null, PageRequest.of(0, 20)).isEmpty());
    }

    @Test
    void listPaginatesWithStableTimestampTieOrderingAndFiltersEveryStatus() {
        var access = new TenantAccess(owner.getId(), TenantRole.VIEWER);
        Delivery tied = deliveries.saveAndFlush(new Delivery(event(owner), endpoint, NOW));
        var sort = Sort.by(Sort.Direction.DESC, "createdAt", "id");
        var expected = List.of(own.getId(), tied.getId()).stream()
                .sorted(java.util.Comparator.comparing(UUID::toString).reversed()).toList();
        entityManager.clear();
        var first = service.findAll(access, null, PageRequest.of(0, 1, sort));
        var second = service.findAll(access, null, PageRequest.of(1, 1, sort));
        assertEquals(expected.get(0), first.getContent().getFirst().id());
        assertEquals(expected.get(1), second.getContent().getFirst().id());
        assertEquals(2, first.getTotalElements());
        assertEquals(2, first.getTotalPages());
        assertTrue(service.findAll(access, null, PageRequest.of(9, 1, sort)).isEmpty());

        for (DeliveryStatus status : DeliveryStatus.values()) {
            Delivery delivery = new Delivery(event(owner), endpoint, NOW.plusSeconds(60));
            if (status != DeliveryStatus.PENDING) delivery.tryStartProcessing();
            if (status == DeliveryStatus.DELIVERED) delivery.markDelivered();
            if (status == DeliveryStatus.FAILED) delivery.markFailed();
            if (status == DeliveryStatus.RETRY_SCHEDULED) delivery.scheduleRetry(NOW.plusSeconds(300));
            deliveries.saveAndFlush(delivery);
            entityManager.clear();
            var filtered = service.findAll(access, status, PageRequest.of(0, 20, sort));
            assertTrue(filtered.getContent().stream().allMatch(response -> response.status() == status));
            assertEquals(status == DeliveryStatus.PENDING ? 3 : 1, filtered.getTotalElements());
            if (status == DeliveryStatus.RETRY_SCHEDULED) {
                assertEquals(NOW.plusSeconds(300), filtered.getContent().getFirst().nextAttemptAt());
            }
        }
    }

    @Test
    void attemptsArePaginatedByAttemptNumberWithResponseFailureAndUnfinishedOutcomes() {
        var access = new TenantAccess(owner.getId(), TenantRole.ADMIN);
        DeliveryAttempt response = new DeliveryAttempt(own, 1, NOW);
        response.completeWithResponse(503, NOW.plusSeconds(1));
        DeliveryAttempt failure = new DeliveryAttempt(own, 2, NOW.plusSeconds(2));
        failure.completeWithFailure("Connection timed out", NOW.plusSeconds(3));
        DeliveryAttempt pending = new DeliveryAttempt(own, 3, NOW.plusSeconds(4));
        attempts.saveAllAndFlush(List.of(response, failure, pending));
        entityManager.clear();
        var sort = Sort.by(Sort.Direction.DESC, "attemptNumber");
        var first = service.findAttempts(access, own.getId(), PageRequest.of(0, 2, sort)).orElseThrow();
        var second = service.findAttempts(access, own.getId(), PageRequest.of(1, 2, sort)).orElseThrow();
        assertEquals(List.of(3, 2), first.getContent().stream().map(attempt -> attempt.attemptNumber()).toList());
        assertEquals(3, first.getTotalElements());
        assertNull(first.getContent().getFirst().completedAt());
        assertNull(first.getContent().getFirst().responseStatus());
        assertNull(first.getContent().getFirst().errorMessage());
        assertEquals("Connection timed out", first.getContent().get(1).errorMessage());
        assertNull(first.getContent().get(1).responseStatus());
        var last = second.getContent().getFirst();
        assertEquals(1, last.attemptNumber());
        assertEquals(503, last.responseStatus());
        assertEquals(NOW.plusSeconds(1), last.completedAt());
        assertEquals(own.getId(), last.deliveryId());
        assertTrue(service.findAttempts(access, own.getId(), PageRequest.of(9, 2, sort)).orElseThrow().isEmpty());
    }

    private Event event(Tenant tenant) {
        return events.saveAndFlush(new Event(tenant, "order.created", JsonMapper.builder().build().readTree("{}"),
                UUID.randomUUID().toString(), NOW));
    }
}
