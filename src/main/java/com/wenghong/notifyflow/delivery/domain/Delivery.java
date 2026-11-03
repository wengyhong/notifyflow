package com.wenghong.notifyflow.delivery.domain;

import java.time.Instant;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.event.domain.Event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "deliveries")
public class Delivery {

    protected Delivery() {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    public UUID getId() {
        return id;
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private WebhookEndpoint endpoint;
    @Enumerated(EnumType.STRING)

    @Column(nullable = false,length = 30)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public DeliveryStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Delivery(Event event, WebhookEndpoint endpoint, Instant createdAt) {
        this.event = event;
        this.endpoint = endpoint;
        this.createdAt = createdAt;
    }

    public Event getEvent() {
        return event;
    }

    public WebhookEndpoint getEndpoint() {
        return endpoint;
    }

    public boolean tryStartProcessing()
    {
        if(status != DeliveryStatus.PENDING)
            return false;

        status = DeliveryStatus.PROCESSING;

        return true;
    }

    public void markDelivered()
    {
        if(status != DeliveryStatus.PROCESSING)
        {
            throw new IllegalStateException("Only a processing delivery can be marked delivered.");
        }

        status = DeliveryStatus.DELIVERED;
    }

    public void markFailed()
    {
        if(status != DeliveryStatus.PROCESSING)
        {
            throw new IllegalStateException("Only a processing delivery can be marked failed.");
        }

        status = DeliveryStatus.FAILED;

    }

}
