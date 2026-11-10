package com.wenghong.notifyflow.delivery.messaging;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.wenghong.notifyflow.delivery.application.DeliveryRequestedEvent;
import com.wenghong.notifyflow.delivery.messaging.outbox.DeliveryOutboxMessage;
import com.wenghong.notifyflow.delivery.messaging.outbox.DeliveryOutboxRepository;

@Component
public class DeliveryRequestedEventListener {

    private final DeliveryOutboxRepository outbox;
    private final Clock clock;

    public DeliveryRequestedEventListener(DeliveryOutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handle(DeliveryRequestedEvent event) {
      outbox.save(new DeliveryOutboxMessage(event.deliveryId(), Instant.now(clock)));
    }

}
