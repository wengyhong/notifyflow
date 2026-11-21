package com.wenghong.notifyflow.identity.security;

import java.util.Objects;
import java.util.UUID;

public record ApiKeyPrincipal(UUID keyId, UUID tenantId) {
    public ApiKeyPrincipal {
        Objects.requireNonNull(keyId);
        Objects.requireNonNull(tenantId);
    }
}
