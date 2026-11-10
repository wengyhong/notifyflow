package com.wenghong.notifyflow.delivery.messaging.outbox;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.wenghong.notifyflow.delivery.messaging.DeliveryJobPublisher;

@Service
public class DeliveryOutboxRelay {

    private final DeliveryOutboxRepository outbox;
    private final DeliveryJobPublisher publisher;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DeliveryOutboxRelay(DeliveryOutboxRepository outbox, DeliveryJobPublisher publisher,
            TransactionTemplate transactions, Clock clock) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.transactions = transactions;
        this.clock = clock;
    }

    public boolean publishNext()
    {
        return transactions.execute(status ->
            outbox.findNextUnpublishedForUpdate().map(message ->{

                publisher.publish(message.getDeliveryId());

                message.markPublished(Instant.now(clock));
                return true;

            }).orElse(false)
        );
    }


}
