package com.wenghong.notifyflow.delivery.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;
import com.wenghong.notifyflow.delivery.persistence.DeliveryAttemptRepository;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;

@Service
public class DeliveryReplayService {
    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher eventPublisher;
    public DeliveryReplayService(DeliveryRepository deliveries, DeliveryAttemptRepository attempts,
            TransactionTemplate transactions, ApplicationEventPublisher eventPublisher) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.transactions = transactions;
        this.eventPublisher = eventPublisher;
    }


    public Optional<Delivery> replay(UUID replay)
    {
        return transactions.execute(status -> deliveries.findByIdForUpdate(replay).map(delivery->{

            if(delivery.getStatus() != DeliveryStatus.FAILED)
                throw new DeliveryReplayNotAllowedException();

            int nextAttemptNumber = attempts.findFirstByDelivery_IdOrderByAttemptNumberDesc(replay).map( prev -> prev.getAttemptNumber()+1).orElseThrow(()-> new IllegalStateException("failed delivery has no attempt history"));

            delivery.requestReplay(nextAttemptNumber);

            eventPublisher.publishEvent(new DeliveryRequestedEvent(replay));

            return  delivery;
        }));
    }
}
