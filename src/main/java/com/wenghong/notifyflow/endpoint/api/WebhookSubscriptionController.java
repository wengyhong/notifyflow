package com.wenghong.notifyflow.endpoint.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wenghong.notifyflow.endpoint.application.DuplicateWebhookSubscriptionException;
import com.wenghong.notifyflow.endpoint.application.WebhookSubscriptionService;
import com.wenghong.notifyflow.endpoint.domain.WebhookSubscription;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/subscriptions")
public class WebhookSubscriptionController {

    private final WebhookSubscriptionService service;

    public WebhookSubscriptionController(WebhookSubscriptionService service) {
        this.service = service;
    }

    @PostMapping("/{id}")
    public ResponseEntity<WebhookSubscriptionResponse> create(

            @PathVariable UUID id,
            @RequestBody @Valid CreateWebhookSubscriptionRequest request) {

        return service.create(id, request.eventType()).map(this::toResponse)
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
