package com.wenghong.notifyflow.delivery.messaging;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DeliveryJobPublisherTests {

    @Test
    void publishSendsDeliveryIdToConfiguredExchangeAndRoutingKey() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        DeliveryJobPublisher publisher = new DeliveryJobPublisher(rabbitTemplate);
        UUID deliveryId = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");

        publisher.publish(deliveryId);

        verify(rabbitTemplate).convertAndSend(
                DeliveryMessagingConfiguration.DELIVERY_EXCHANGE,
                DeliveryMessagingConfiguration.DELIVERY_ROUTING_KEY,
                deliveryId.toString());
    }
}
