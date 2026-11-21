package com.wenghong.notifyflow.endpoint.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wenghong.notifyflow.endpoint.application.DuplicateWebhookSubscriptionException;
import com.wenghong.notifyflow.endpoint.application.WebhookSubscriptionService;
import com.wenghong.notifyflow.endpoint.domain.WebhookSubscription;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.security.AppUserDetails;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/subscriptions")
public class WebhookSubscriptionController {

    private final WebhookSubscriptionService service;
    private final TenantAccessService tenantAccessService;

    public WebhookSubscriptionController(
            WebhookSubscriptionService service,
            TenantAccessService tenantAccessService) {
        this.service = service;
        this.tenantAccessService = tenantAccessService;
    }

    @PostMapping("/{id}")
    public ResponseEntity<WebhookSubscriptionResponse> create(
            @AuthenticationPrincipal AppUserDetails user,
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @PathVariable UUID id,
            @RequestBody @Valid CreateWebhookSubscriptionRequest request) {

        TenantAccess access = tenantAccessService.requireAccess(tenantId, user.getUserId());

        return service.create(access, id, request.eventType()).map(this::toResponse)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private WebhookSubscriptionResponse toResponse(WebhookSubscription sub) {
        return new WebhookSubscriptionResponse(sub.getId(), sub.getEndpoint().getId(), sub.getEventType());
    }

    @ExceptionHandler(DuplicateWebhookSubscriptionException.class)
    public ProblemDetail handleDuplicateSubscription(DuplicateWebhookSubscriptionException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, exception.getMessage());
    }
}
