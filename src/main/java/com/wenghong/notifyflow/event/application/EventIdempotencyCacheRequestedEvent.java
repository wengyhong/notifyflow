package com.wenghong.notifyflow.event.application;

import java.util.UUID;

public record EventIdempotencyCacheRequestedEvent(
        UUID tenantId,
        String idempotencyKey,
        EventSnapshot snapshot
) {
}
