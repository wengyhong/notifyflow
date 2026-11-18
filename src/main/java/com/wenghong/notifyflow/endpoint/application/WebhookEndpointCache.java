package com.wenghong.notifyflow.endpoint.application;

import java.util.Optional;
import java.util.UUID;

public interface WebhookEndpointCache {

    Optional<WebhookEndpointSnapshot> findById(UUID id);

    void put(WebhookEndpointSnapshot snapshot);
    void evict(UUID id);
}
