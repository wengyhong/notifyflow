package com.wenghong.notifyflow.delivery.messaging;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class DeliveryJobPublisher {

    private final RabbitTemplate rabbitTemplate;

    public DeliveryJobPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(UUID deliveryId) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());

        rabbitTemplate.convertAndSend(
                DeliveryMessagingConfiguration.DELIVERY_EXCHANGE,
                DeliveryMessagingConfiguration.DELIVERY_ROUTING_KEY,
                deliveryId.toString(),
                correlation);

        CorrelationData.Confirm confirmation = awaitConfirm(correlation);

        if (!confirmation.ack()) {
            throw new AmqpException("RabbitMQ rejected delivery job pulication:" + confirmation.reason());
        }

        if (correlation.getReturned() != null) {
            throw new AmqpException("Delivery job could not be routed to a queue");
        }
    }

    private CorrelationData.Confirm awaitConfirm(CorrelationData correlation) {
        try {
            return correlation.getFuture().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Interrupted while waiting for publisher confirmation", error);
        } catch (ExecutionException | TimeoutException error) {
            throw new AmqpException("Could not confirm delivery job publication", error);
        }
    }
}
