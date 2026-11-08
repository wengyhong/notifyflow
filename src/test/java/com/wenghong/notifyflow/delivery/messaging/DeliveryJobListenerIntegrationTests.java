package com.wenghong.notifyflow.delivery.messaging;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.wenghong.notifyflow.delivery.application.DeliveryProcessingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(
        classes = DeliveryJobListenerIntegrationTests.RabbitTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.config.name=notifyflow-rabbitmq-listener-integration-test")
class DeliveryJobListenerIntegrationTests {

    @Autowired
    private DeliveryJobPublisher publisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitMQContainer rabbit;

    @MockitoBean
    private DeliveryProcessingService service;

    @BeforeEach
    void configureDeadLetterPolicyOnIsolatedBroker() throws Exception {
        String definition = """
                {"dead-letter-exchange":"%s","dead-letter-routing-key":"%s"}
                """.formatted(
                        DeliveryMessagingConfiguration.DELIVERY_DEAD_LETTER_EXCHANGE,
                        DeliveryMessagingConfiguration.DELIVERY_DEAD_LETTER_ROUTING_KEY).strip();
        // This targets only this test's temporary broker, whose default vhost is '/'.
        var result = rabbit.execInContainer("rabbitmqctl", "set_policy", "-p", "/",
                "--apply-to", "queues", "notifyflow-delivery-dlx",
                "^notifyflow\\.deliveries\\.jobs$", definition);
        assertEquals(0, result.getExitCode(), result.getStdout() + result.getStderr());
    }

    @Test
    void publishedJobReachesListenerAndInvokesProcessingService() throws InterruptedException {
        UUID deliveryId = UUID.randomUUID();

        publisher.publish(deliveryId);

        // Wait for asynchronous delivery through the real broker and listener container.
        verify(service, timeout(10_000)).process(deliveryId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "a8a1d32a-a014-47bb-bf82-f31ea55e4acg", ""})
    void malformedJobIsDeadLetteredAndDoesNotBlockNextValidJob(String body) throws Exception {
        rabbitTemplate.convertAndSend(DeliveryMessagingConfiguration.DELIVERY_EXCHANGE,
                DeliveryMessagingConfiguration.DELIVERY_ROUTING_KEY, body);

        Message rejected = rabbitTemplate.receive(
                DeliveryMessagingConfiguration.DELIVERY_DEAD_LETTER_QUEUE, 10_000);

        assertNotNull(rejected, "Rejected job did not reach the dead-letter queue");
        assertEquals(body, new String(rejected.getBody(), StandardCharsets.UTF_8));
        assertEquals(DeliveryMessagingConfiguration.DELIVERY_DEAD_LETTER_EXCHANGE,
                rejected.getMessageProperties().getReceivedExchange());
        assertEquals(DeliveryMessagingConfiguration.DELIVERY_DEAD_LETTER_ROUTING_KEY,
                rejected.getMessageProperties().getReceivedRoutingKey());
        List<?> deaths = assertInstanceOf(List.class,
                rejected.getMessageProperties().getHeaders().get("x-death"));
        assertEquals(1, deaths.size());
        Map<?, ?> death = assertInstanceOf(Map.class, deaths.getFirst());
        assertEquals("rejected", death.get("reason").toString());
        assertEquals(DeliveryMessagingConfiguration.DELIVERY_QUEUE, death.get("queue").toString());
        assertEquals(1L, ((Number) death.get("count")).longValue());
        verifyNoInteractions(service);

        UUID validId = UUID.randomUUID();
        publisher.publish(validId);
        verify(service, timeout(10_000)).process(validId);
        verifyNoMoreInteractions(service);
        assertNull(rabbitTemplate.receive(DeliveryMessagingConfiguration.DELIVERY_DEAD_LETTER_QUEUE));
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
