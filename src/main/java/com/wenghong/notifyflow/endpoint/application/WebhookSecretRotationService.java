package com.wenghong.notifyflow.endpoint.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;

@Service
public class WebhookSecretRotationService {

    private final WebhookEndpointRepository repository;
    private final WebhookSecretGenerator generator;
    private final Clock clock;
    public WebhookSecretRotationService(WebhookEndpointRepository repository, WebhookSecretGenerator generator,
            Clock clock) {
        this.repository = repository;
        this.generator = generator;
        this.clock = clock;
    }

    @Transactional
    public Optional<WebhookEndpoint> rotate(UUID id)
    {
        return repository.findByIdForUpdate(id).map(endpoint-> {

            Instant now = clock.instant();

            if(endpoint.hasActivePreviousSigningSecret(now))
                throw new SecretRotationInProgressException();

            endpoint.rotateSigningSecret(generator.generate(), now, now.plusSeconds(3600));

            return endpoint;

        });
    }


}
