package com.wenghong.notifyflow.delivery.application;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryAttempt;
import com.wenghong.notifyflow.delivery.http.WebhookHttpSender;
import com.wenghong.notifyflow.delivery.persistence.DeliveryAttemptRepository;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;

//This service will coordinate loading a delivery, sending its webhook, and recording the outcome. We’ll introduce those operations incrementally, then connect the listener.
@Service
public class DeliveryProcessingService {

    private final TransactionTemplate transactions;

    private final WebhookHttpSender sender;

    private final DeliveryRepository repository;
    private final Clock clock;
    private final DeliveryAttemptRepository attempts;

    public DeliveryProcessingService(DeliveryRepository repository, Clock clock, TransactionTemplate transactions,
            DeliveryAttemptRepository attempts, WebhookHttpSender sender) {
        this.repository = repository;
        this.clock = clock;
        this.transactions = transactions;
        this.attempts = attempts;
        this.sender = sender;
    }

    public Optional<DeliveryWork> claim(UUID deliveryId) {
        return transactions.execute(status -> repository.findByIdForUpdate(deliveryId).flatMap(delivery -> {
            if (!delivery.tryStartProcessing()) {
                return Optional.empty();
            }

            DeliveryAttempt attempt = attempts.saveAndFlush(new DeliveryAttempt(delivery, 1, Instant.now(clock)));

            return Optional.of(new DeliveryWork(
                    delivery.getId(),
                    attempt.getId(),
                    delivery.getEndpoint().getUrl(),
                    delivery.getEvent().getPayload().deepCopy()));
        }));
    }

    public void recordResponse(UUID deliveryId, UUID attemptId, int responseStatus) {
        transactions.executeWithoutResult(status -> {

            Delivery delivery = repository.findByIdForUpdate(deliveryId)
                    .orElseThrow(() -> new IllegalStateException("Delivery not found."));

            DeliveryAttempt attempt = attempts.findById(attemptId)
                    .orElseThrow(() -> new IllegalStateException("Delivery attempt not found."));

            if (!attempt.getDelivery().getId().equals(deliveryId)) {
                throw new IllegalArgumentException("Attempt does not belong to this delivery.");
            }
            attempt.completeWithResponse(responseStatus, Instant.now(clock));

            if (responseStatus >= 200 && responseStatus <= 299) {
                delivery.markDelivered();
            } else {
                delivery.markFailed();
            }

        });

    }

    public void recordFailure(UUID deliveryId, UUID attemptId, String errorMessage) {
        transactions.executeWithoutResult(status -> {

            Delivery delivery = repository.findByIdForUpdate(deliveryId)
                    .orElseThrow(() -> new IllegalStateException("Delivery not found."));

            DeliveryAttempt attempt = attempts.findById(attemptId)
                    .orElseThrow(() -> new IllegalStateException("Delivery attempt not found."));

            if (!attempt.getDelivery().getId().equals(deliveryId)) {
                throw new IllegalArgumentException("Attempt does not belong to this delivery.");
            }

            attempt.completeWithFailure(errorMessage, Instant.now(clock));

            delivery.markFailed();

        });
    }

    public void process(UUID deliveryId) throws InterruptedException {
        Optional<DeliveryWork> claimed = claim(deliveryId);

        if (claimed.isEmpty())
            return;

        DeliveryWork work = claimed.get();
        int responseStatus;
        try {
            responseStatus = sender.send(work);
        } catch (IOException ioException) {
            String message = ioException.getMessage();

            if (message == null || message.isBlank()) {
                message = ioException.getClass().getSimpleName();
            }
            recordFailure(deliveryId, work.attemptId(), message);
            return;
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw interruptedException;
        }

        recordResponse(deliveryId, work.attemptId(), responseStatus);

    }

}
