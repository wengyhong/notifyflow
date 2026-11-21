package com.wenghong.notifyflow.identity.api;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wenghong.notifyflow.identity.application.ApiKeyCreationResult;
import com.wenghong.notifyflow.identity.application.ApiKeyService;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.security.AppUserDetails;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@RestController
@RequestMapping("/api/key")
public class ApiKeyController {

    private final ApiKeyService service;
    private final TenantAccessService tenantAccessService;

    public ApiKeyController(ApiKeyService service,
            TenantAccessService tenantAccessService) {
        this.service = service;
        this.tenantAccessService = tenantAccessService;
    }

    @PostMapping

    public ResponseEntity<ApiKeyCreatedResponse> create(

            @AuthenticationPrincipal AppUserDetails user,
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @Valid @RequestBody CreateApiKeyRequest body) {
        var tenant = tenantAccessService.requireAccess(tenantId, user.getUserId());
        ApiKeyCreationResult result = service.create(tenant, body.name());

        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(toResponse(result));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<Void> revoke(
            @AuthenticationPrincipal AppUserDetails user,
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @PathVariable UUID id)

    {
        var access = tenantAccessService.requireAccess(tenantId, user.getUserId());

        if (service.revoke(access, id)) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.notFound().build();
    }

    public ApiKeyCreatedResponse toResponse(ApiKeyCreationResult result) {
        return new ApiKeyCreatedResponse(result.id(), result.name(), result.rawKey(), result.createdAt());
    }
}
