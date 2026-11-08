package com.wenghong.notifyflow.delivery.messaging;

import java.util.UUID;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.wenghong.notifyflow.delivery.application.DeliveryProcessingService;

@Component
public class DeliveryJobListener {
    private final DeliveryProcessingService service;

    public DeliveryJobListener(DeliveryProcessingService service) {
        this.service = service;
    }

    @RabbitListener(queues = DeliveryMessagingConfiguration.DELIVERY_QUEUE)
    public void receive(String deliveryId) throws InterruptedException {
        UUID id;
        try {
            id = UUID.fromString(deliveryId);
        } catch (IllegalArgumentException error) {
            throw new AmqpRejectAndDontRequeueException("Invalid delivery ID", error);
        }
        service.process(id);
    }

}
