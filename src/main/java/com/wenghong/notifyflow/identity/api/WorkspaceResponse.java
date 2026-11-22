package com.wenghong.notifyflow.identity.api;

import java.util.UUID;

import com.wenghong.notifyflow.identity.domain.TenantRole;

public record WorkspaceResponse(
        UUID tenantId,
        String name,
        TenantRole role) {
}
