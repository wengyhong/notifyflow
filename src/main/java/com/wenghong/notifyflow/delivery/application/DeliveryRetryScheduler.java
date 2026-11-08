package com.wenghong.notifyflow.delivery.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;
import com.wenghong.notifyflow.delivery.messaging.DeliveryJobPublisher;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;

@Component
public class DeliveryRetryScheduler {

    private final Clock clock;
    private final DeliveryJobPublisher publisher;
    private final DeliveryRepository repository;


    public DeliveryRetryScheduler(Clock clock, DeliveryJobPublisher publisher, DeliveryRepository repository) {
        this.clock = clock;
        this.publisher = publisher;
        this.repository = repository;
    }


    @Scheduled(fixedDelayString = "${notifyflow.delivery.retry-poll-delay:1000}")
    public void publishDueRetries() {
        repository.findDueRetryIds(DeliveryStatus.RETRY_SCHEDULED, Instant.now(clock), PageRequest.of(0, 100))
                .forEach(publisher::publish);
    }

}
