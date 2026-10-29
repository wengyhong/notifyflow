package com.wenghong.notifyflow.endpoint.persistence;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class WebhookEndpointRepositoryTests {

    @Autowired
    private WebhookEndpointRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findAllPaginatesNewestFirstAndBreaksTimestampTiesById() {
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint older = new WebhookEndpoint(
                "Older", "https://example.com/older", now.minusSeconds(60));
        WebhookEndpoint first = new WebhookEndpoint(
                "First", "https://example.com/first", now);
        WebhookEndpoint second = new WebhookEndpoint(
                "Second", "https://example.com/second", now);
        repository.saveAllAndFlush(List.of(first, older, second));

        List<UUID> newestIds = List.of(first, second).stream()
                .sorted(Comparator.comparing((WebhookEndpoint endpoint) ->
                        endpoint.getId().toString()).reversed())
                .map(WebhookEndpoint::getId)
                .toList();
        entityManager.clear();
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt", "id");

        Page<WebhookEndpoint> firstPage = repository.findAll(PageRequest.of(0, 2, sort));
        Page<WebhookEndpoint> secondPage = repository.findAll(PageRequest.of(1, 2, sort));

        assertEquals(newestIds, firstPage.getContent().stream()
                .map(WebhookEndpoint::getId).toList());
        assertEquals(0, firstPage.getNumber());
        assertEquals(2, firstPage.getSize());
        assertEquals(3L, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(List.of(older.getId()), secondPage.getContent().stream()
                .map(WebhookEndpoint::getId).toList());
        assertEquals(1, secondPage.getNumber());
        assertEquals(3L, secondPage.getTotalElements());
        assertEquals(2, secondPage.getTotalPages());
    }

    @Test
    void savesAndReloadsEndpointWithGeneratedIdAndAllFields() {
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = new WebhookEndpoint(
                "Order notifications", "https://example.com/webhooks/orders", now);

        WebhookEndpoint saved = repository.saveAndFlush(endpoint);
        UUID id = saved.getId();
        assertNotNull(id);

        // Force findById to read PostgreSQL instead of returning the managed entity.
        entityManager.clear();

        WebhookEndpoint reloaded = repository.findById(id).orElseThrow();

        assertEquals(id, reloaded.getId());
        assertEquals("Order notifications", reloaded.getName());
        assertEquals("https://example.com/webhooks/orders", reloaded.getUrl());
        assertTrue(reloaded.isEnabled());
        assertEquals(now, reloaded.getCreatedAt());
        assertEquals(now, reloaded.getUpdatedAt());
    }
}
