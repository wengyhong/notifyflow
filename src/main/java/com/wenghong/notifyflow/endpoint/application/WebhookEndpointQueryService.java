package com.wenghong.notifyflow.endpoint.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.identity.application.TenantAccess;


@Service
public class WebhookEndpointQueryService {

    private final WebhookEndpointRepository repository;
    private final WebhookEndpointCache cache;

    public WebhookEndpointQueryService(WebhookEndpointRepository repository, WebhookEndpointCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    @Transactional (readOnly = true)

    public Optional<WebhookEndpointSnapshot> findById(TenantAccess access, UUID id) {
        Optional<WebhookEndpointSnapshot> cachePoint = cache.findById(id)
                .filter(snapshot -> access.tenantId().equals(snapshot.tenantId()));

        if (cachePoint.isPresent())
            return cachePoint;

        return repository.findByIdAndTenant_Id(id, access.tenantId()).map(WebhookEndpointSnapshot::from)
                .map(snapshot -> {
                    cache.put(snapshot);
                    return snapshot;
                });

    }
}
