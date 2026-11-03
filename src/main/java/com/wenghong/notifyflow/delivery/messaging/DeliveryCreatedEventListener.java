package com.wenghong.notifyflow.delivery.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.wenghong.notifyflow.delivery.application.DeliveryCreatedEvent;

@Component
public class DeliveryCreatedEventListener {
    private final DeliveryJobPublisher publisher;

    public DeliveryCreatedEventListener(DeliveryJobPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(DeliveryCreatedEvent event)
    {
        publisher.publish(event.deliveryId());
    }


}
