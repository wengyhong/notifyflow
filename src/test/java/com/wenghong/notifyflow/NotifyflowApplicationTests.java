package com.wenghong.notifyflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;

@SpringBootTest
@Import({PostgresTestConfiguration.class, NotifyflowApplicationTests.RabbitTestConfiguration.class})
class NotifyflowApplicationTests {

	@Test
	void contextLoads() {
	}

    @TestConfiguration(proxyBeanMethods = false)
    static class RabbitTestConfiguration {

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbitContainer() {
            return new RabbitMQContainer("rabbitmq:4.4.0-management");
        }
    }

}
