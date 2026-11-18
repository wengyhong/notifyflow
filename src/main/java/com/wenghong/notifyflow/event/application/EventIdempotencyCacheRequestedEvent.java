package com.wenghong.notifyflow.event.application;

public record EventIdempotencyCacheRequestedEvent(
        String idempotencyKey,
        EventSnapshot snapshot
) {
}
