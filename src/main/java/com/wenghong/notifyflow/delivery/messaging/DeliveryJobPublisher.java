package com.wenghong.notifyflow.delivery.messaging;

import java.util.UUID;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class DeliveryJobPublisher {

    private final RabbitTemplate rabbitTemplate;

    public DeliveryJobPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(UUID deliveryId)
    {
        rabbitTemplate.convertAndSend(
            DeliveryMessagingConfiguration.DELIVERY_EXCHANGE,
            DeliveryMessagingConfiguration.DELIVERY_ROUTING_KEY,
            deliveryId.toString()
        );
    }
}
