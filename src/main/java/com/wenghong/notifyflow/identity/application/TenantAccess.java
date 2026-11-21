package com.wenghong.notifyflow.identity.application;

import java.util.Objects;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;

import com.wenghong.notifyflow.identity.domain.TenantRole;

public record TenantAccess(UUID tenantId,
        TenantRole role) {

    public TenantAccess {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(role);
    }

    public void requireAdmin() {
    if (role != TenantRole.ADMIN) {
        throw new AccessDeniedException(
                "Tenant administrator access is required.");
    }
}

}
