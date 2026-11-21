package com.wenghong.notifyflow.event.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.wenghong.notifyflow.identity.domain.Tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "events")
public class Event {

    protected Event() {
    }

    @GeneratedValue(strategy = GenerationType.UUID)
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    public Tenant getTenant() {
        return tenant;
    }

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode payload;
    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private Event(String eventType, JsonNode payload, String idempotencyKey, Instant createdAt) {
        this.eventType = eventType;
        this.payload = payload;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    public Event(
            Tenant tenant,
            String eventType,
            JsonNode payload,
            String idempotencyKey,
            Instant createdAt) {

        this(eventType, payload, idempotencyKey, createdAt);
        this.tenant = Objects.requireNonNull(tenant);
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
