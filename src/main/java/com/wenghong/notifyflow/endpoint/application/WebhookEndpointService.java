package com.wenghong.notifyflow.endpoint.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookEndpointService {

    private final WebhookEndpointRepository repository;
    private final WebhookSecretGenerator generator;

    private final ApplicationEventPublisher publisher;
    private final WebhookUrlPolicy policy;

    public WebhookEndpointService(WebhookEndpointRepository repository, WebhookSecretGenerator generator,
            WebhookUrlPolicy policy, ApplicationEventPublisher publisher) {
        this.repository = repository;
        this.generator = generator;
        this.publisher = publisher;
        this.policy = policy;
    }

    @Transactional
    public WebhookEndpoint create(String name, String url) {

        policy.validate(url);

        WebhookEndpoint point = new WebhookEndpoint(name, url, Instant.now(), generator.generate());

        return repository.save(point);
    }

    @Transactional(readOnly = true)
    public Optional<WebhookEndpoint> findById(UUID id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public Page<WebhookEndpoint> findAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional
    public Optional<WebhookEndpoint> changeEnabled(UUID id, boolean enabled) {
        Optional<WebhookEndpoint> point = repository.findById(id);

            return point.map(endpoint -> {
               if (endpoint.isEnabled() != enabled) {
            endpoint.changeEnabled(enabled, Instant.now());

            publisher.publishEvent(
                    new WebhookEndpointChangedEvent(endpoint.getId()));
        }

                return endpoint;
            });

}

}
