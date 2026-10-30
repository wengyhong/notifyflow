package com.wenghong.notifyflow.event.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wenghong.notifyflow.event.application.EventIngestionService;
import com.wenghong.notifyflow.event.application.IdempotencyConflictException;
import com.wenghong.notifyflow.event.domain.Event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventIngestionService service;

    public EventController(EventIngestionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EventAcceptedResponse ingest(

            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody CreateEventRequest request

    ) {
        Event event = service.ingest(request.type(), request.payload(), idempotencyKey);

        return toResponse(event);
    }

    private EventAcceptedResponse toResponse(Event event) {
        return new EventAcceptedResponse(event.getId(), event.getEventType(), event.getCreatedAt());

    }

    @ExceptionHandler (IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyDetail(IdempotencyConflictException exception
    )
    {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

}
