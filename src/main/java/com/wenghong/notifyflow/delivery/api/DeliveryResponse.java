package com.wenghong.notifyflow.delivery.api;

import java.time.Instant;
import java.util.UUID;

import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;

public record DeliveryResponse(
        UUID id,
        UUID eventId,
        UUID endpointId,
        DeliveryStatus status,
        Instant createdAt,
        Instant nextAttemptAt,
        int retryCycleStartAttempt) {
}
