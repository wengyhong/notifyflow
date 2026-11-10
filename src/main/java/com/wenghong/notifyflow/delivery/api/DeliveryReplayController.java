package com.wenghong.notifyflow.delivery.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wenghong.notifyflow.delivery.application.DeliveryReplayService;
import com.wenghong.notifyflow.delivery.domain.Delivery;

import com.wenghong.notifyflow.delivery.application.DeliveryReplayNotAllowedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryReplayController {

    private final DeliveryReplayService service;

    public DeliveryReplayController(DeliveryReplayService service) {
        this.service = service;
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<DeliveryReplayAcceptedResponse> replay(
            @PathVariable UUID id) {
        return service.replay(id).map(this::toResponse).map(response -> ResponseEntity.accepted().body(response))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    DeliveryReplayAcceptedResponse toResponse(Delivery delivery) {
        return new DeliveryReplayAcceptedResponse(delivery.getId(), delivery.getStatus());
    }

    @ExceptionHandler(DeliveryReplayNotAllowedException.class)
    public ProblemDetail handleReplayNotAllowed(
            DeliveryReplayNotAllowedException exception) {

        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, exception.getMessage());
    }

}
