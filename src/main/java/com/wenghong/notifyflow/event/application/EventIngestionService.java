package com.wenghong.notifyflow.event.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.wenghong.notifyflow.delivery.application.DeliveryRequestedEvent;
import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;

import com.wenghong.notifyflow.endpoint.persistence.WebhookSubscriptionRepository;
import com.wenghong.notifyflow.event.domain.Event;
import com.wenghong.notifyflow.event.persistence.EventRepository;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import com.wenghong.notifyflow.identity.security.ApiKeyPrincipal;

import tools.jackson.databind.JsonNode;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

@Service
public class EventIngestionService {
    private final EventIdempotencyCache idempotencyCache;
    private final ApplicationEventPublisher eventPublisher;
    private final TenantRepository tenants;
    private final EventRepository events;
    private final WebhookSubscriptionRepository subscriptions;
    private final DeliveryRepository deliveries;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public EventIngestionService(EventRepository events, WebhookSubscriptionRepository subscriptions,
            DeliveryRepository deliveries, Clock clock, TransactionTemplate transactions,
            ApplicationEventPublisher eventPublisher, EventIdempotencyCache cache, TenantRepository tenants) {
        this.events = events;
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.clock = clock;
        this.transactions = transactions;
        this.eventPublisher = eventPublisher;
        this.idempotencyCache = cache;
        this.tenants = tenants;
    }

    private Event createEventAndDeliveries(UUID tenantId, String eventType, JsonNode payload, String idempotencyKey) {
        Instant now = Instant.now(clock);

        var tenant = tenants.getReferenceById(tenantId);
        Event event =

                events.saveAndFlush(new Event(tenant, eventType, payload, idempotencyKey, now));

        var pendingDeliveries = subscriptions
                .findAllByEndpoint_Tenant_IdAndEventTypeAndEndpointEnabledTrue(tenantId, eventType).stream()
                .map(sub -> {

                    return new Delivery(event, sub.getEndpoint(), now);
                }).toList();

        var savedDeliveries = deliveries.saveAll(pendingDeliveries);

        savedDeliveries.forEach(delivery -> eventPublisher.publishEvent(new DeliveryRequestedEvent(delivery.getId())));

        return event;

    }

    private EventSnapshot reuseExistingEvent(
            EventSnapshot existing,
            String eventType,
            JsonNode payload) {
        if (!existing.eventType().equals(eventType)
                || !existing.payload().equals(payload)) {
            throw new IdempotencyConflictException();
        }

        return existing;
    }

    public EventSnapshot ingest(
            TenantAccess access,
            String eventType,
            JsonNode payload,
            String idempotencyKey) {

        access.requireAdmin();

        return ingestForTenant(
                access.tenantId(), eventType, payload, idempotencyKey);
    }

    public EventSnapshot ingestForProducer(
            ApiKeyPrincipal principal,
            String eventType,
            JsonNode payload,
            String idempotencyKey) {

        return ingestForTenant(
                principal.tenantId(), eventType, payload, idempotencyKey);
    }

    private EventSnapshot ingestForTenant(
            UUID tenantId,
            String eventType,
            JsonNode payload,
            String idempotencyKey) {

        var cached = idempotencyCache.findByKey(tenantId, idempotencyKey);
        if (cached.isPresent()) {
            return reuseExistingEvent(cached.get(), eventType, payload);
        }

        Event event = events.findByTenant_IdAndIdempotencyKey(
                tenantId, idempotencyKey)
                .map(existing -> reuseExistingEvent(existing, eventType, payload))
                .orElseGet(() -> createOrReuseAfterRace(
                        tenantId, eventType, payload, idempotencyKey));

        EventSnapshot snapshot = EventSnapshot.from(event);

        eventPublisher.publishEvent(
                new EventIdempotencyCacheRequestedEvent(
                        tenantId, idempotencyKey, snapshot));

        return snapshot;
    }

    private Event reuseExistingEvent(Event existing, String eventType, JsonNode payload) {
        if (!existing.getEventType().equals(eventType) || !existing.getPayload().equals(payload)) {
            throw new IdempotencyConflictException();
        }

        return existing;
    }

    private boolean isIdempotencyKeyViolation(Throwable error) {
        Throwable current = error;

        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && "uq_events_tenant_idempotency_key"
                            .equals(violation.getConstraintName())) {
                return true;
            }

            current = current.getCause();

        }

        return false;
    }

    private Event createOrReuseAfterRace(UUID tenantId,
            String eventType, JsonNode payload, String idempotencyKey) {
        try {
            return transactions
                    .execute(status -> createEventAndDeliveries(tenantId, eventType, payload, idempotencyKey));

        } catch (DataIntegrityViolationException error) {
            if (!isIdempotencyKeyViolation(error)) {
                throw error;
            }

            return events.findByTenant_IdAndIdempotencyKey(tenantId, idempotencyKey)
                    .map(exist -> reuseExistingEvent(exist, eventType, payload)).orElseThrow(() -> error);

        }
    }
}
