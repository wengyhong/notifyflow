package com.wenghong.notifyflow.endpoint.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    protected WebhookEndpoint() {

    }

    public WebhookEndpoint(String name, String url, Instant now) {

        this.name = name;
        this.url = url;
        createdAt = now;
        updatedAt = now;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "url", nullable = false, columnDefinition = "text")
    private String url;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void changeEnabled(boolean enabled, Instant now) {
        if (this.enabled == enabled) {
            return;
        }

        this.enabled = enabled;
        updatedAt = now;
    }

}
