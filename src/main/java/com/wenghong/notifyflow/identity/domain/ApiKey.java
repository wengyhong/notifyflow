package com.wenghong.notifyflow.identity.domain;

import java.time.Instant;
import java.util.Objects;
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
@Table(name = "api_keys")
public class ApiKey {

    public ApiKey(
        Tenant tenant,
        String name,
        String keyHash,
        Instant now) {

    this.tenant = Objects.requireNonNull(tenant);
    this.name = Objects.requireNonNull(name);
    this.keyHash = Objects.requireNonNull(keyHash);
    this.createdAt = Objects.requireNonNull(now);
}

    protected ApiKey() {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public boolean isActive()
    {
        return revokedAt == null;
    }

    public void revoke(Instant now)
    {
        Objects.requireNonNull(now);
        if(isActive())
        {
            revokedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public String getName() {
        return name;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
