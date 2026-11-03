package com.wenghong.notifyflow.delivery.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DeliveryMessagingConfiguration {

    public static final String DELIVERY_EXCHANGE = "notifyflow.deliveries";
    public static final String DELIVERY_QUEUE = "notifyflow.deliveries.jobs";
    public static final String DELIVERY_ROUTING_KEY = "delivery.requested";

    @Bean
    public DirectExchange deliveryExchange() {
        return new DirectExchange(DELIVERY_EXCHANGE, true, false);
    }

    @Bean
    public Queue deliveryQueue() {
        return QueueBuilder.durable(DELIVERY_QUEUE).build();
    }

    @Bean
    public Binding deliveryBinding(Queue deliveryQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(deliveryQueue).to(deliveryExchange).with(DELIVERY_ROUTING_KEY);
    }
}
