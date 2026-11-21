package com.wenghong.notifyflow.endpoint.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookEndpointService {
    private final TenantRepository tenants;
    private final WebhookEndpointRepository repository;
    private final WebhookSecretGenerator generator;

    private final ApplicationEventPublisher publisher;
    private final WebhookUrlPolicy policy;

    public WebhookEndpointService(
            WebhookEndpointRepository repository,
            WebhookSecretGenerator generator,
            WebhookUrlPolicy policy,
            ApplicationEventPublisher publisher,
            TenantRepository tenants) {
        this.repository = repository;
        this.generator = generator;
        this.policy = policy;
        this.publisher = publisher;
        this.tenants = tenants;
    }

    @Transactional
    public WebhookEndpoint create(TenantAccess access, String name, String url) {
        access.requireAdmin();

        policy.validate(url);

        Tenant tenant = tenants.getReferenceById(access.tenantId());

        WebhookEndpoint point = new WebhookEndpoint(tenant, name, url, Instant.now(), generator.generate());

        return repository.save(point);
    }

    @Transactional(readOnly = true)
    public Page<WebhookEndpoint> findAll(TenantAccess access, Pageable pageable) {
        return repository.findAllByTenant_Id(access.tenantId(), pageable);
    }

    @Transactional
    public Optional<WebhookEndpoint> changeEnabled(TenantAccess access, UUID id, boolean enabled) {
        access.requireAdmin();


        return repository.findByIdAndTenant_Id(id, access.tenantId()).map(endpoint -> {
            if (endpoint.isEnabled() != enabled) {
                endpoint.changeEnabled(enabled, Instant.now());

                publisher.publishEvent(
                        new WebhookEndpointChangedEvent(endpoint.getId()));
            }

            return endpoint;
        });

    }

}
