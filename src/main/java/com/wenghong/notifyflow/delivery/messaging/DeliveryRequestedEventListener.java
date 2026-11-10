package com.wenghong.notifyflow.delivery.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.wenghong.notifyflow.delivery.application.DeliveryRequestedEvent;

@Component
public class DeliveryRequestedEventListener {
    private final DeliveryJobPublisher publisher;

    public DeliveryRequestedEventListener(DeliveryJobPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(DeliveryRequestedEvent event)
    {
        publisher.publish(event.deliveryId());
    }


}
