package com.wenghong.notifyflow.event.api;

import java.util.UUID;

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
import com.wenghong.notifyflow.event.application.EventSnapshot;
import com.wenghong.notifyflow.event.application.IdempotencyConflictException;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.security.AppUserDetails;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventIngestionService service;
private final TenantAccessService tenantAccessService;

    public EventController(EventIngestionService service, TenantAccessService tenantAccessService) {
        this.service = service;
        this.tenantAccessService = tenantAccessService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EventAcceptedResponse ingest(

        @AuthenticationPrincipal  AppUserDetails user,
        @RequestHeader ("X-Tenant-ID") UUID tenantId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody CreateEventRequest request

    ) {

        TenantAccess access =
            tenantAccessService.requireAccess(tenantId, user.getUserId());

        EventSnapshot event = service.ingest(access,request.type(), request.payload(), idempotencyKey);

        return toResponse(event);
    }


    @ExceptionHandler (IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyDetail(IdempotencyConflictException exception
    )
    {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    private EventAcceptedResponse toResponse(EventSnapshot event) {
    return new EventAcceptedResponse(
            event.id(),
            event.eventType(),
            event.createdAt());
}

}
