package com.wenghong.notifyflow.endpoint.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "webhook_subscriptions")
public class WebhookSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private WebhookEndpoint endpoint;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    protected WebhookSubscription() {
    }

    public UUID getId() {
        return id;
    }

    public WebhookEndpoint getEndpoint() {
        return endpoint;
    }

    public String getEventType() {
        return eventType;
    }

    public WebhookSubscription(WebhookEndpoint endpoint, String eventType) {
        this.endpoint = endpoint;
        this.eventType = eventType;
    }

}
