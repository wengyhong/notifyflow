package com.wenghong.notifyflow.endpoint.application;

import java.time.Instant;
import java.net.InetAddress;
import java.util.Base64;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.PostgresTestConfiguration;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, WebhookEndpointService.class,
        WebhookSecretGenerator.class, WebhookUrlPolicy.class, WebhookAddressPolicy.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class WebhookEndpointServiceIntegrationTests {

    private static final String TEST_SIGNING_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Autowired
    private WebhookEndpointRepository repository;

    @Autowired
    private WebhookEndpointService service;

    @MockitoBean
    private WebhookHostResolver resolver;

    @Autowired
    private TenantRepository tenants;

    private TenantAccess access;

    @BeforeEach
    void createOwningTenant() {
        Tenant tenant = tenants.saveAndFlush(new Tenant("Company A", Instant.parse("2026-11-20T00:00:00Z")));
        access = new TenantAccess(tenant.getId(), TenantRole.ADMIN);
    }

    @AfterEach
    void cleanIsolatedTenants() {
        // Committed endpoint tests delete their endpoints before removing the parent tenant.
        tenants.deleteAllInBatch();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user:password@example.com/webhook",
            "https://example.com/webhook#fragment",
            "https://example.com:0/webhook",
            "ftp://example.com/webhook"
    })
    void rejectedUrlDoesNotCreateEndpoint(String url) {
        long countBefore = repository.count();

        assertThrows(InvalidWebhookUrlException.class, () -> service.create(access, "Orders", url));

        assertEquals(countBefore, repository.count());
        verifyNoInteractions(resolver);
    }

    @Test
    void blockedDnsResultDoesNotCreateEndpoint() throws Exception {
        when(resolver.resolve("example.com")).thenReturn(new InetAddress[] {
                InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")
        });
        long countBefore = repository.count();

        InvalidWebhookUrlException error = assertThrows(InvalidWebhookUrlException.class,
                () -> service.create(access, "Orders", "https://example.com/webhooks"));

        assertEquals("Webhook host resolves to a blocked address", error.getMessage());
        assertEquals(countBefore, repository.count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createCommitsGeneratedSecret() throws Exception {
        when(resolver.resolve("example.com")).thenReturn(new InetAddress[] {
                InetAddress.getByName("8.8.8.8")
        });
        WebhookEndpoint created = service.create(access, "Orders", "https://example.com/webhooks");

        try {
            WebhookEndpoint reloaded = repository.findById(created.getId()).orElseThrow();
            assertEquals(44, reloaded.getSigningSecret().length());
            assertEquals(32, Base64.getDecoder().decode(reloaded.getSigningSecret()).length);
            assertEquals(created.getSigningSecret(), reloaded.getSigningSecret());
            assertEquals(access.tenantId(), reloaded.getTenant().getId());
        } finally {
            repository.deleteById(created.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void changeEnabledCommitsStateThroughDirtyChecking(boolean enabled) {
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = new WebhookEndpoint(tenants.getReferenceById(access.tenantId()),
                "Orders", "https://example.com/webhooks", createdAt, TEST_SIGNING_SECRET);
        endpoint.changeEnabled(!enabled, createdAt);
        UUID id = repository.saveAndFlush(endpoint).getId();

        try {
            // No test transaction: the service must open and commit its own transaction.
            WebhookEndpoint result = service.changeEnabled(access, id, enabled).orElseThrow();
            assertEquals(enabled, result.isEnabled());

            // This lookup runs after the service transaction has committed.
            WebhookEndpoint reloaded = repository.findById(id).orElseThrow();
            assertEquals(enabled, reloaded.isEnabled());
            assertEquals(createdAt, reloaded.getCreatedAt());
            assertEquals(TEST_SIGNING_SECRET, reloaded.getSigningSecret());
            assertNotEquals(createdAt, reloaded.getUpdatedAt());

            Instant previousUpdatedAt = reloaded.getUpdatedAt();
            service.changeEnabled(access, id, enabled).orElseThrow();
            WebhookEndpoint unchanged = repository.findById(id).orElseThrow();
            assertEquals(enabled, unchanged.isEnabled());
            assertEquals(previousUpdatedAt, unchanged.getUpdatedAt());
            assertEquals(createdAt, unchanged.getCreatedAt());
            assertEquals(TEST_SIGNING_SECRET, unchanged.getSigningSecret());
        } finally {
            // The test deliberately commits, so clean up its row explicitly.
            repository.deleteById(id);
        }
    }
}
