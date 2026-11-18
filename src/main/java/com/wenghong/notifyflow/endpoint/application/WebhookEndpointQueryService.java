package com.wenghong.notifyflow.endpoint.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;

@Service
public class WebhookEndpointQueryService {

    private final WebhookEndpointRepository repository;
    private final WebhookEndpointCache cache;

    public WebhookEndpointQueryService(WebhookEndpointRepository repository, WebhookEndpointCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public Optional<WebhookEndpointSnapshot> findById(UUID id) {
        Optional<WebhookEndpointSnapshot> cachePoint = cache.findById(id);

        if (cachePoint.isPresent())
            return cachePoint;

        return repository.findById(id).map(WebhookEndpointSnapshot::from)
                .map(snapshot -> {
                    cache.put(snapshot);
                    return snapshot;
                });

    }
}
