package com.wenghong.notifyflow.event.application;

import java.util.Optional;
import java.util.UUID;

public interface EventIdempotencyCache {

    Optional<EventSnapshot> findByKey(UUID tenantId, String idempotencyKey);

    void put(UUID tenantId,String idempotencyKey, EventSnapshot snapshot);
}
