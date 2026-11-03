package com.wenghong.notifyflow.delivery.messaging;

import java.util.UUID;

import com.wenghong.notifyflow.delivery.application.DeliveryProcessingService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.testcontainers.service.connection.ServiceConnectionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest(
        classes = DeliveryJobListenerIntegrationTests.RabbitTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.config.name=notifyflow-rabbitmq-listener-integration-test")
class DeliveryJobListenerIntegrationTests {

    @Autowired
    private DeliveryJobPublisher publisher;

    @MockitoBean
    private DeliveryProcessingService service;

    @Test
    void publishedJobReachesListenerAndInvokesProcessingService() throws InterruptedException {
        UUID deliveryId = UUID.randomUUID();

        publisher.publish(deliveryId);

        // Wait for asynchronous delivery through the real broker and listener container.
        verify(service, timeout(10_000)).process(deliveryId);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRabbit
    @Import({DeliveryMessagingConfiguration.class, DeliveryJobPublisher.class, DeliveryJobListener.class})
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
