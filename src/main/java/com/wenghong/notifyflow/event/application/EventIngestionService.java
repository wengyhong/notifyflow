package com.wenghong.notifyflow.event.application;

import java.time.Clock;
import java.time.Instant;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.wenghong.notifyflow.delivery.application.DeliveryRequestedEvent;
import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;

import com.wenghong.notifyflow.endpoint.persistence.WebhookSubscriptionRepository;
import com.wenghong.notifyflow.event.domain.Event;
import com.wenghong.notifyflow.event.persistence.EventRepository;

import tools.jackson.databind.JsonNode;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

@Service
public class EventIngestionService {
    private final EventIdempotencyCache idempotencyCache;
    private final ApplicationEventPublisher eventPublisher;
    private EventRepository events;
    private WebhookSubscriptionRepository subscriptions;
    private DeliveryRepository deliveries;
    private Clock clock;
    private TransactionTemplate transactions;

    public EventIngestionService(EventRepository events, WebhookSubscriptionRepository subscriptions,
            DeliveryRepository deliveries, Clock clock, TransactionTemplate transactions,
            ApplicationEventPublisher eventPublisher, EventIdempotencyCache cache) {
        this.events = events;
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.clock = clock;
        this.transactions = transactions;
        this.eventPublisher = eventPublisher;
        this.idempotencyCache = cache;
    }

    // private Event createEventAndDeliveries(
    // String eventType, JsonNode payload, String idempotencyKey) {

    // Instant now = Instant.now(clock);

    // Event event = events.saveAndFlush(
    // new Event(eventType, payload, idempotencyKey, now));

    // List<WebhookSubscription> matches =
    // subscriptions.findAllByEventTypeAndEndpointEnabledTrue(eventType);

    // List<Delivery> pendingDeliveries = matches.stream()
    // .map(subscription ->
    // new Delivery(event, subscription.getEndpoint(), now))
    // .toList();

    // deliveries.saveAll(pendingDeliveries);

    // return event;
    // }

    private Event createEventAndDeliveries(String eventType, JsonNode payload, String idempotencyKey) {
        Instant now = Instant.now(clock);

        Event event =

                events.saveAndFlush(new Event(eventType, payload, idempotencyKey, now));

        var pendingDeliveries = subscriptions.findAllByEventTypeAndEndpointEnabledTrue(eventType).stream().map(sub -> {

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

    public EventSnapshot ingest(String eventType, JsonNode payload, String idempotencyKey) {

        var cached = idempotencyCache.findByKey(idempotencyKey);
        if (cached.isPresent()) {
            return reuseExistingEvent(cached.get(), eventType, payload);
        }
        Event event = events.findByIdempotencyKey(idempotencyKey)
                .map(exist -> reuseExistingEvent(exist, eventType, payload))
                .orElseGet(() -> createOrReuseAfterRace(eventType, payload, idempotencyKey));

        EventSnapshot snapshot = EventSnapshot.from(event);
        eventPublisher.publishEvent(new EventIdempotencyCacheRequestedEvent(idempotencyKey, snapshot));
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
                    && "uq_events_idempotency_key"
                            .equals(violation.getConstraintName())) {
                return true;
            }

            current = current.getCause();

        }

        return false;
    }

    private Event createOrReuseAfterRace(
            String eventType, JsonNode payload, String idempotencyKey) {
        try {
            return transactions.execute(status -> createEventAndDeliveries(eventType, payload, idempotencyKey));

        } catch (DataIntegrityViolationException error) {
            if (!isIdempotencyKeyViolation(error)) {
                throw error;
            }

            return events.findByIdempotencyKey(idempotencyKey)
                    .map(exist -> reuseExistingEvent(exist, eventType, payload)).orElseThrow(() -> error);

        }
    }
}
