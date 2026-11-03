package com.wenghong.notifyflow.delivery.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.testcontainers.service.connection.ServiceConnectionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
        classes = DeliveryJobPublisherIntegrationTests.RabbitTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.config.name=notifyflow-rabbitmq-integration-test")
class DeliveryJobPublisherIntegrationTests {

    @Autowired
    private DeliveryJobPublisher publisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin admin;


//     The test proceeds like this:
// 1. Starts temporary RabbitMQ. Testcontainers creates an isolated broker. @ServiceConnection supplies its connection details to Spring.
// 2. Loads your messaging beans. The test includes DeliveryMessagingConfiguration and DeliveryJobPublisher, plus RabbitMQ auto-configuration. It doesn’t load the whole NotifyFlow application.
// 3. Checks the queue exists and is empty. Opening the connection triggers RabbitAdmin to declare your exchange, queue, and binding.
// 4. Publishes a fixed delivery UUID. This calls your actual publisher.publish(deliveryId) method.
// 5. Reads from the queue, waiting up to five seconds for a message.
// 6. Checks the result:
//    - The body contains the expected UUID.
//    - The content type is text/plain.
//    - The received exchange and routing key match our configuration.
//    - The queue is empty after the message is consumed.
    @Test
    void publishRoutesDeliveryIdToConfiguredQueue() {
        // Opening the connection triggers RabbitAdmin's automatic declarations.
        QueueInformation queue = admin.getQueueInfo(DeliveryMessagingConfiguration.DELIVERY_QUEUE);
        assertNotNull(queue);
        assertEquals(0, queue.getMessageCount());
        UUID deliveryId = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");

        publisher.publish(deliveryId);

        Message message = rabbitTemplate.receive(DeliveryMessagingConfiguration.DELIVERY_QUEUE, 5_000);
        assertNotNull(message, "Published delivery job did not reach the queue within five seconds");
        assertEquals(deliveryId.toString(), new String(message.getBody(), StandardCharsets.UTF_8));
        assertEquals("text/plain", message.getMessageProperties().getContentType());
        assertEquals(DeliveryMessagingConfiguration.DELIVERY_EXCHANGE,
                message.getMessageProperties().getReceivedExchange());
        assertEquals(DeliveryMessagingConfiguration.DELIVERY_ROUTING_KEY,
                message.getMessageProperties().getReceivedRoutingKey());

        QueueInformation remaining = admin.getQueueInfo(DeliveryMessagingConfiguration.DELIVERY_QUEUE);
        assertNotNull(remaining);
        assertEquals(0, remaining.getMessageCount());
    }

    @Configuration(proxyBeanMethods = false)
    @Import({DeliveryMessagingConfiguration.class, DeliveryJobPublisher.class})
    @ImportAutoConfiguration({RabbitAutoConfiguration.class, ServiceConnectionAutoConfiguration.class})
    static class RabbitTestConfiguration {

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbit() {
            return new RabbitMQContainer("rabbitmq:4.4.0-management")
                    .withAdminUser("notifyflow_test")
                    .withAdminPassword("notifyflow_test_password");
        }
    }
}
