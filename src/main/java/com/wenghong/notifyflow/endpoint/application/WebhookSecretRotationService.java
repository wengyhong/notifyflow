package com.wenghong.notifyflow.endpoint.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.identity.application.TenantAccess;

@Service
public class WebhookSecretRotationService {

    private final WebhookEndpointRepository repository;
    private final WebhookSecretGenerator generator;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    public WebhookSecretRotationService(WebhookEndpointRepository repository, WebhookSecretGenerator generator,
            Clock clock, ApplicationEventPublisher publisher) {
        this.repository = repository;
        this.generator = generator;
        this.clock = clock;
        this.publisher = publisher;
    }

    @Transactional
    public Optional<WebhookEndpoint> rotate(TenantAccess access, UUID id) {
        access.requireAdmin();

        return repository.findByIdAndTenantIdForUpdate(id, access.tenantId())
                .map(endpoint -> {

                    Instant now = clock.instant();

                    if (endpoint.hasActivePreviousSigningSecret(now))
                        throw new SecretRotationInProgressException();

                    endpoint.rotateSigningSecret(generator.generate(), now, now.plusSeconds(3600));
                    publisher.publishEvent(new WebhookEndpointChangedEvent(endpoint.getId()));

                    return endpoint;

                });
    }

}
