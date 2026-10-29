package com.wenghong.notifyflow.endpoint.application;

import java.time.Instant;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, WebhookEndpointService.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class WebhookEndpointServiceIntegrationTests {

    @Autowired
    private WebhookEndpointRepository repository;

    @Autowired
    private WebhookEndpointService service;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void changeEnabledCommitsStateThroughDirtyChecking(boolean enabled) {
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Orders", "https://example.com/webhooks", createdAt);
        endpoint.changeEnabled(!enabled, createdAt);
        UUID id = repository.saveAndFlush(endpoint).getId();

        try {
            // No test transaction: the service must open and commit its own transaction.
            WebhookEndpoint result = service.changeEnabled(id, enabled).orElseThrow();
            assertEquals(enabled, result.isEnabled());

            // This lookup runs after the service transaction has committed.
            WebhookEndpoint reloaded = repository.findById(id).orElseThrow();
            assertEquals(enabled, reloaded.isEnabled());
            assertEquals(createdAt, reloaded.getCreatedAt());
            assertNotEquals(createdAt, reloaded.getUpdatedAt());

            Instant previousUpdatedAt = reloaded.getUpdatedAt();
            service.changeEnabled(id, enabled).orElseThrow();
            WebhookEndpoint unchanged = repository.findById(id).orElseThrow();
            assertEquals(enabled, unchanged.isEnabled());
            assertEquals(previousUpdatedAt, unchanged.getUpdatedAt());
            assertEquals(createdAt, unchanged.getCreatedAt());
        } finally {
            // The test deliberately commits, so clean up its row explicitly.
            repository.deleteById(id);
        }
    }
}
