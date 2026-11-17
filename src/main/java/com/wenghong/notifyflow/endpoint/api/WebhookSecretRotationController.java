package com.wenghong.notifyflow.endpoint.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wenghong.notifyflow.endpoint.application.SecretRotationInProgressException;
import com.wenghong.notifyflow.endpoint.application.WebhookSecretRotationService;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;

@RestController
@RequestMapping("/api/endpoints")
public class WebhookSecretRotationController {

    private final WebhookSecretRotationService service;

    public WebhookSecretRotationController(WebhookSecretRotationService service) {
        this.service = service;
    }

    @PostMapping("/{id}/signing-secret/rotate")
    public ResponseEntity<WebhookSecretRotatedResponse> rotate(
            @PathVariable UUID id) {
        return service.rotate(id).map(this::toResponse).map(ResponseEntity::ok)
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
