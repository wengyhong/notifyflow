package com.wenghong.notifyflow.delivery.api;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wenghong.notifyflow.delivery.application.DeliveryQueryService;
import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.security.AppUserDetails;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryQueryController {
    private final DeliveryQueryService service;
    private final TenantAccessService tenantAccessService;

    public DeliveryQueryController(DeliveryQueryService service, TenantAccessService tenantAccessService) {
        this.service = service;
        this.tenantAccessService = tenantAccessService;
    }

    @GetMapping
    public ResponseEntity<DeliveryPageResponse> findAll(
            @AuthenticationPrincipal AppUserDetails user,
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @RequestParam(required = false) DeliveryStatus status,
            @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
            Pageable pageable) {
        var access = tenantAccessService.requireAccess(tenantId, user.getUserId());
        var page = service.findAll(access, status, pageable);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new DeliveryPageResponse(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeliveryResponse> findById(
            @AuthenticationPrincipal AppUserDetails user,
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @PathVariable UUID id) {
        var access = tenantAccessService.requireAccess(tenantId, user.getUserId());
        return service.findById(access, id)
                .map(response -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/attempts")
    public ResponseEntity<DeliveryAttemptPageResponse> findAttempts(
            @AuthenticationPrincipal AppUserDetails user,
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @PathVariable UUID id,
            @PageableDefault(size = 20, sort = "attemptNumber", direction = Sort.Direction.DESC)
            Pageable pageable) {
        var access = tenantAccessService.requireAccess(tenantId, user.getUserId());
        return service.findAttempts(access, id, pageable)
                .map(page -> new DeliveryAttemptPageResponse(page.getContent(), page.getNumber(), page.getSize(),
                        page.getTotalElements(), page.getTotalPages()))
                .map(response -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
