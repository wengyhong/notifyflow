package com.wenghong.notifyflow.delivery.api;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptResponse(
        UUID id, UUID deliveryId, int attemptNumber, Instant startedAt, Instant completedAt,
        Integer responseStatus, String errorMessage) {
}
