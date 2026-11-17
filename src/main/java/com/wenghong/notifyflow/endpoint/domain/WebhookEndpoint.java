package com.wenghong.notifyflow.endpoint.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    protected WebhookEndpoint() {

    }

    public WebhookEndpoint(String name, String url, Instant now, String signingSecret) {

        this.name = name;
        this.url = url;
        createdAt = now;
        updatedAt = now;
        this.signingSecret = Objects.requireNonNull(signingSecret);

    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "url", nullable = false, columnDefinition = "text")
    private String url;

    @Column(name = "signing_secret", length = 44, nullable = false)
    private String signingSecret;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "previous_signing_secret", length = 44)
    private String previousSigningSecret;

    @Column(name = "previous_signing_secret_expires_at")
    private Instant previousSigningSecretExpiresAt;

    @Version
    @Column (nullable = false)
    private long version;
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getSigningSecret() {
        return signingSecret;
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

    public String getPreviousSigningSecret() {
        return previousSigningSecret;
    }

    public Instant getPreviousSigningSecretExpiresAt() {
        return previousSigningSecretExpiresAt;
    }

    public void changeEnabled(boolean enabled, Instant now) {
        if (this.enabled == enabled) {
            return;
        }

        this.enabled = enabled;
        updatedAt = now;
    }

    public boolean hasActivePreviousSigningSecret(Instant now) {
        Objects.requireNonNull(now);
        return previousSigningSecret != null && now.isBefore(previousSigningSecretExpiresAt);
    }

    public void rotateSigningSecret(
            String newSecret,
            Instant now,
            Instant previousSecretExpiresAt) {
        Objects.requireNonNull(newSecret);
        Objects.requireNonNull(now);
        Objects.requireNonNull(previousSecretExpiresAt);

        if (newSecret.isBlank() || newSecret.equals(signingSecret)) {
            throw new IllegalArgumentException(
                    "The new signing secret must be different and nonblank");
        }

        if (!previousSecretExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException(
                    "Previous secret expiry must be in the future");
        }

        if (hasActivePreviousSigningSecret(now)) {
            throw new IllegalStateException(
                    "A signing secret rotation is already in progress");
        }

        this.previousSigningSecret = this.signingSecret;
        this.signingSecret = newSecret;
        this.previousSigningSecretExpiresAt = previousSecretExpiresAt;
        this.updatedAt = now;

    }

}
