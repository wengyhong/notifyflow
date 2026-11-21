package com.wenghong.notifyflow.endpoint.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.application.WebhookEndpointQueryService;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointService;
import com.wenghong.notifyflow.endpoint.application.WebhookSecretGenerator;
import com.wenghong.notifyflow.endpoint.application.WebhookUrlPolicy;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.configuration.SecurityConfiguration;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantMembership;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.TenantMembershipRepository;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import com.wenghong.notifyflow.identity.security.AppUserDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Keep both application services real: MVC must enforce the database membership and role guards.
@WebMvcTest(WebhookEndpointController.class)
@Import({SecurityConfiguration.class, TenantAccessService.class, WebhookEndpointService.class})
class TenantEndpointCreationHttpTests {
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String BODY = """
            {"name":"Orders","url":"https://example.com/webhook"}
            """;

    @Autowired private MockMvc mvc;
    @MockitoBean private WebhookEndpointQueryService queries;
    @MockitoBean private TenantMembershipRepository memberships;
    @MockitoBean private TenantRepository tenants;
    @MockitoBean private WebhookEndpointRepository endpoints;
    @MockitoBean private WebhookUrlPolicy policy;
    @MockitoBean private WebhookSecretGenerator secrets;

    @Test
    void administratorCreatesEndpointOwnedByVerifiedTenant() throws Exception {
        Tenant tenant = membership(TenantRole.ADMIN);
        when(tenants.getReferenceById(TENANT_ID)).thenReturn(tenant);
        when(secrets.generate()).thenReturn(SECRET);
        when(endpoints.save(any(WebhookEndpoint.class))).thenAnswer(call -> {
            WebhookEndpoint endpoint = call.getArgument(0);
            ReflectionTestUtils.setField(endpoint, "id", UUID.randomUUID());
            return endpoint;
        });

        // Body fields and role headers cannot override the tenant selected and verified by the server.
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID)
                        .header("X-Tenant-Role", "VIEWER")
                        .content("""
                                {"name":"Orders","url":"https://example.com/webhook",
                                 "tenantId":"00000000-0000-0000-0000-000000000099","role":"VIEWER"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("id").isNotEmpty())
                .andExpect(jsonPath("signingSecret").value(SECRET));

        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verify(policy).validate("https://example.com/webhook");
        ArgumentCaptor<WebhookEndpoint> saved = ArgumentCaptor.forClass(WebhookEndpoint.class);
        verify(endpoints).save(saved.capture());
        assertSame(tenant, saved.getValue().getTenant());
    }

    @Test
    void viewerCannotPromoteThemselvesUsingAHeader() throws Exception {
        membership(TenantRole.VIEWER);
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID)
                        .header("X-Tenant-Role", "ADMIN").content(BODY))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoInteractions(tenants, endpoints, policy, secrets);
    }

    @Test
    void nonmemberCannotBorrowAnotherUsersIdentityOrRole() throws Exception {
        UUID otherUser = UUID.randomUUID();
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID)
                        .header("X-User-ID", otherUser).header("X-Tenant-Role", "ADMIN")
                        .content(BODY))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoInteractions(tenants, endpoints, policy, secrets);
    }

    @Test
    void membershipInOneTenantDoesNotAuthorizeCreationInAnother() throws Exception {
        membership(TenantRole.ADMIN);
        UUID otherTenant = UUID.randomUUID();
        when(memberships.findByTenant_IdAndUser_Id(otherTenant, USER_ID)).thenReturn(Optional.empty());
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", otherTenant).content(BODY))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(otherTenant, USER_ID);
        verifyNoInteractions(tenants, endpoints, policy, secrets);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void missingOrMalformedTenantHeaderIsRejectedBeforeMembershipLookup(String header) throws Exception {
        var request = request().with(csrf()).content(BODY);
        if (!header.isEmpty()) request.header("X-Tenant-ID", header);
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(memberships, tenants, endpoints, policy, secrets);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrInvalidCsrfIsRejectedBeforeMembershipLookup(boolean invalidToken) throws Exception {
        var request = request().header("X-Tenant-ID", TENANT_ID).content(BODY);
        if (invalidToken) request.with(csrf().useInvalidToken());
        mvc.perform(request).andExpect(status().isForbidden());
        verifyNoInteractions(memberships, tenants, endpoints, policy, secrets);
    }

    @Test
    void anonymousRequestIsRejectedBeforeMembershipLookup() throws Exception {
        mvc.perform(request().with(anonymous()).with(csrf())
                        .header("X-Tenant-ID", TENANT_ID).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(memberships, tenants, endpoints, policy, secrets);
    }

    private MockHttpServletRequestBuilder request() {
        return post("/api/endpoints").with(user(new AppUserDetails(account())))
                .contentType(MediaType.APPLICATION_JSON);
    }

    private AppUser account() {
        AppUser account = new AppUser("admin@example.com", "unused-test-hash", NOW);
        ReflectionTestUtils.setField(account, "id", USER_ID);
        return account;
    }

    private Tenant membership(TenantRole role) {
        Tenant tenant = new Tenant("Company A", NOW);
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID))
                .thenReturn(Optional.of(new TenantMembership(tenant, account(), role, NOW)));
        return tenant;
    }
}
