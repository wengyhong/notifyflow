package com.wenghong.notifyflow.endpoint.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wenghong.notifyflow.endpoint.application.SecretRotationInProgressException;
import com.wenghong.notifyflow.endpoint.application.WebhookSecretRotationService;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.security.AppUserDetails;

@RestController
@RequestMapping("/api/endpoints")
public class WebhookSecretRotationController {

    private final WebhookSecretRotationService service;

    private final TenantAccessService tenantAccessService;

    public WebhookSecretRotationController(WebhookSecretRotationService service, TenantAccessService accessService) {
        this.service = service;
        this.tenantAccessService = accessService;
    }

    @PostMapping("/{id}/signing-secret/rotate")
    public ResponseEntity<WebhookSecretRotatedResponse> rotate(
        @AuthenticationPrincipal  AppUserDetails user,
        @RequestHeader("X-Tenant-ID") UUID tenantId,

            @PathVariable UUID id) {

                  TenantAccess access =
            tenantAccessService.requireAccess(tenantId, user.getUserId());


        return service.rotate(access, id).map(this::toResponse).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private WebhookSecretRotatedResponse toResponse(WebhookEndpoint endpoint) {
        return new WebhookSecretRotatedResponse(endpoint.getId(), endpoint.getSigningSecret(),
                endpoint.getPreviousSigningSecretExpiresAt());
    }

    @ExceptionHandler(SecretRotationInProgressException.class)
    public ProblemDetail handleRotationInProgress(
            SecretRotationInProgressException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage());
    }
}
