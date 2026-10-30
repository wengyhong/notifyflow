package com.wenghong.notifyflow.endpoint.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wenghong.notifyflow.endpoint.domain.WebhookSubscription;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.endpoint.persistence.WebhookSubscriptionRepository;
import org.hibernate.exception.ConstraintViolationException;

@Service
public class WebhookSubscriptionService {

    private final WebhookEndpointRepository endpoints;
    private final WebhookSubscriptionRepository subscriptions;

    public WebhookSubscriptionService(WebhookEndpointRepository endpoints,
            WebhookSubscriptionRepository subscriptions) {
        this.endpoints = endpoints;
        this.subscriptions = subscriptions;
    }

    @Transactional
    public Optional<WebhookSubscription> create(UUID id, String eventType) {

        return endpoints.findById(id).map(endpoint -> {

            WebhookSubscription subscription = new WebhookSubscription(endpoint, eventType);

            try {
                return subscriptions.saveAndFlush(subscription);
            } catch (DataIntegrityViolationException exception) {
                if (isDuplicateSubscription(exception)) {
                    throw new DuplicateWebhookSubscriptionException(exception);
                }

                throw exception;
            }
        });

    }

    private boolean isDuplicateSubscription(Throwable error) {
        Throwable current = error;

        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && "uq_webhook_subscriptions_endpoint_event_type"
                            .equals(violation.getConstraintName())) {
                return true;
            }

            current = current.getCause();

        }

        return false;
    }

}
