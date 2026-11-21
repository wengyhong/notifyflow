package com.wenghong.notifyflow.event.api;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wenghong.notifyflow.event.application.EventIngestionService;
import com.wenghong.notifyflow.event.application.IdempotencyConflictException;
import com.wenghong.notifyflow.identity.security.ApiKeyPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/producer/events")
public class ProducerEventController {

    private final EventIngestionService service;

    public ProducerEventController(EventIngestionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EventAcceptedResponse ingest(
            @AuthenticationPrincipal ApiKeyPrincipal principal,
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody CreateEventRequest request) {

        var event = service.ingestForProducer(
                principal,
                request.type(),
                request.payload(),
                idempotencyKey);

        return new EventAcceptedResponse(
                event.id(), event.eventType(), event.createdAt());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleConflict(
            IdempotencyConflictException exception) {

        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, exception.getMessage());
    }
}