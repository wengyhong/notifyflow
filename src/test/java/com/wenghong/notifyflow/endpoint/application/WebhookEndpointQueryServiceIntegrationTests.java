package com.wenghong.notifyflow.endpoint.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, WebhookEndpointQueryService.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebhookEndpointQueryServiceIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    @Autowired private WebhookEndpointQueryService service;
    @Autowired private WebhookEndpointRepository endpoints;
    @Autowired private TenantRepository tenants;
    @MockitoBean private WebhookEndpointCache cache;
    private UUID endpointId;
    private TenantAccess owner;
    private TenantAccess other;

    @BeforeEach
    void saveCommittedFixtures() {
        Tenant first = tenants.saveAndFlush(new Tenant("Company A", NOW));
        Tenant second = tenants.saveAndFlush(new Tenant("Company B", NOW));
        owner = new TenantAccess(first.getId(), TenantRole.VIEWER);
        other = new TenantAccess(second.getId(), TenantRole.ADMIN);
        endpointId = endpoints.saveAndFlush(new WebhookEndpoint(first, "Orders", "https://example.com/webhook",
                NOW, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")).getId();
        when(cache.findById(endpointId)).thenReturn(Optional.empty());
    }

    @AfterEach
    void cleanIsolatedDatabase() {
        endpoints.deleteAllInBatch();
        tenants.deleteAllInBatch();
    }

    @Test
    void serviceTransactionLoadsLazyOwnerAndCachesOwnedSnapshot() {
        // There is no surrounding test transaction: the service must provide its own.
        WebhookEndpointSnapshot snapshot = service.findById(owner, endpointId).orElseThrow();
        assertEquals(endpointId, snapshot.id());
        assertEquals(owner.tenantId(), snapshot.tenantId());
        assertEquals("Orders", snapshot.name());
        assertEquals(NOW, snapshot.createdAt());
        verify(cache).put(snapshot);
    }

    @Test
    void administratorInAnotherTenantCannotReadOrCacheTheEndpoint() {
        assertTrue(service.findById(other, endpointId).isEmpty());
        verify(cache, never()).put(any());
    }
}
