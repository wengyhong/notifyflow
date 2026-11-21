package com.wenghong.notifyflow.event.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.delivery.application.DeliveryRetryScheduler;
import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.messaging.outbox.DeliveryOutboxRepository;
import com.wenghong.notifyflow.delivery.messaging.outbox.DeliveryOutboxScheduler;
import com.wenghong.notifyflow.delivery.persistence.DeliveryAttemptRepository;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.domain.WebhookSubscription;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.endpoint.persistence.WebhookSubscriptionRepository;
import com.wenghong.notifyflow.event.application.EventIdempotencyCache;
import com.wenghong.notifyflow.event.application.EventSnapshot;
import com.wenghong.notifyflow.event.persistence.EventRepository;
import com.wenghong.notifyflow.identity.application.ApiKeyCreationResult;
import com.wenghong.notifyflow.identity.application.ApiKeyService;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.ApiKeyRepository;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, ProducerEventHttpIntegrationTests.RabbitConfiguration.class})
class ProducerEventHttpIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    private static final String SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private ApiKeyService keyManagement;
    @Autowired private ApiKeyRepository keys;
    @Autowired private TenantRepository tenants;
    @Autowired private EventRepository events;
    @Autowired private WebhookEndpointRepository endpoints;
    @Autowired private WebhookSubscriptionRepository subscriptions;
    @Autowired private DeliveryRepository deliveries;
    @Autowired private DeliveryAttemptRepository attempts;
    @Autowired private DeliveryOutboxRepository outbox;
    @MockitoBean private EventIdempotencyCache cache;
    // Keep background dispatch paused while verifying durable rows created by HTTP ingestion.
    @MockitoBean private DeliveryRetryScheduler retries;
    @MockitoBean private DeliveryOutboxScheduler relay;
    private Tenant firstTenant;
    private Tenant secondTenant;
    private ApiKeyCreationResult firstKey;
    private ApiKeyCreationResult secondKey;
    private UUID firstEndpoint;
    private UUID secondEndpoint;

    @BeforeEach
    void createCommittedProducerFixtures() {
        firstTenant = tenants.saveAndFlush(new Tenant("Company A", NOW));
        secondTenant = tenants.saveAndFlush(new Tenant("Company B", NOW));
        firstKey = keyManagement.create(new TenantAccess(firstTenant.getId(), TenantRole.ADMIN), "Producer A");
        secondKey = keyManagement.create(new TenantAccess(secondTenant.getId(), TenantRole.ADMIN), "Producer B");
        WebhookEndpoint first = endpoints.saveAndFlush(new WebhookEndpoint(firstTenant,
                "Receiver A", "https://example.com/a", NOW, SECRET));
        WebhookEndpoint second = endpoints.saveAndFlush(new WebhookEndpoint(secondTenant,
                "Receiver B", "https://example.com/b", NOW, SECRET));
        firstEndpoint = first.getId();
        secondEndpoint = second.getId();
        subscriptions.saveAllAndFlush(List.of(new WebhookSubscription(first, "order.confirmed"),
                new WebhookSubscription(second, "order.confirmed")));
    }

    @AfterEach
    void cleanIsolatedCommittedData() {
        outbox.deleteAllInBatch();
        attempts.deleteAllInBatch();
        deliveries.deleteAllInBatch();
        events.deleteAllInBatch();
        subscriptions.deleteAllInBatch();
        endpoints.deleteAllInBatch();
        keys.deleteAllInBatch();
        tenants.deleteAllInBatch();
    }

    @Test
    void realKeysPublishIndependentEventsAndRouteOnlyToOwnedEndpoints() throws Exception {
        String idempotency = "shared-producer-key";
        var firstResponse = mvc.perform(request(firstKey.rawKey(), idempotency, 128)
                        .header("X-Tenant-ID", secondTenant.getId()))
                .andExpect(status().isAccepted()).andExpect(cookie().doesNotExist("JSESSIONID"))
                .andExpect(result -> assertNull(result.getRequest().getSession(false))).andReturn();
        UUID firstId = UUID.fromString(json.readTree(firstResponse.getResponse().getContentAsString()).get("id").asString());
        mvc.perform(request(firstKey.rawKey(), idempotency, 128))
                .andExpect(status().isAccepted()).andExpect(jsonPath("id").value(firstId.toString()));
        var secondResponse = mvc.perform(request(secondKey.rawKey(), idempotency, 256))
                .andExpect(status().isAccepted()).andReturn();
        UUID secondId = UUID.fromString(json.readTree(secondResponse.getResponse().getContentAsString()).get("id").asString());

        assertNotEquals(firstId, secondId);
        assertEquals(firstId, events.findByTenant_IdAndIdempotencyKey(firstTenant.getId(), idempotency).orElseThrow().getId());
        assertEquals(secondId, events.findByTenant_IdAndIdempotencyKey(secondTenant.getId(), idempotency).orElseThrow().getId());
        assertEquals(2L, events.count());
        assertEquals(2L, deliveries.count());
        assertEquals(2L, outbox.count());
        for (Delivery delivery : deliveries.findAll()) {
            UUID expected = delivery.getEvent().getId().equals(firstId) ? firstEndpoint : secondEndpoint;
            assertEquals(expected, delivery.getEndpoint().getId());
        }
        mvc.perform(request(firstKey.rawKey(), idempotency, 999)).andExpect(status().isConflict());
        assertEquals(2L, events.count());
        assertEquals(2L, deliveries.count());
        assertEquals(2L, outbox.count());
    }

    @Test
    void revokedKeyCannotReuseEvenAWarmIdempotencyResult() throws Exception {
        String idempotency = "revoked-producer-retry";
        mvc.perform(request(firstKey.rawKey(), idempotency, 128)).andExpect(status().isAccepted());
        EventSnapshot snapshot = EventSnapshot.from(events
                .findByTenant_IdAndIdempotencyKey(firstTenant.getId(), idempotency).orElseThrow());
        when(cache.findByKey(firstTenant.getId(), idempotency)).thenReturn(Optional.of(snapshot));
        assertTrue(keyManagement.revoke(new TenantAccess(firstTenant.getId(), TenantRole.ADMIN), firstKey.id()));

        mvc.perform(request(firstKey.rawKey(), idempotency, 128)).andExpect(status().isUnauthorized());

        verify(cache).findByKey(firstTenant.getId(), idempotency);
        assertEquals(1L, events.count());
        assertEquals(1L, deliveries.count());
        assertEquals(1L, outbox.count());
    }

    private MockHttpServletRequestBuilder request(String rawKey, String idempotency, int orderId) {
        return post("/api/producer/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey)
                .header("Idempotency-Key", idempotency).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"order.confirmed\",\"payload\":{\"orderId\":%d}}".formatted(orderId));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RabbitConfiguration {
        @Bean
        @ServiceConnection
        RabbitMQContainer rabbitContainer() {
            return new RabbitMQContainer("rabbitmq:4.4.0-management");
        }
    }
}
