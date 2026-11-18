package com.wenghong.notifyflow.event.application;

import java.util.Optional;

public interface EventIdempotencyCache {

    Optional<EventSnapshot> findByKey(String idempotencyKey);

    void put(String idempotencyKey, EventSnapshot snapshot);
}
