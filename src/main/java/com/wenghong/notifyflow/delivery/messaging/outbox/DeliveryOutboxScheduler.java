package com.wenghong.notifyflow.delivery.messaging.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DeliveryOutboxScheduler {

    private final DeliveryOutboxRelay relay;

    public DeliveryOutboxScheduler(DeliveryOutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${notifyflow.delivery.outbox-poll-delay:1000}")
    public void publishPending()
    {
        for(int i=0; i<100; i++)
        {
            if(!relay.publishNext())
                break;

        }
    }


}
