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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookEndpointController.class)
@Import({SecurityConfiguration.class, TenantAccessService.class, WebhookEndpointService.class})
class TenantEndpointEnabledHttpTests {
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID ENDPOINT_ID = UUID.randomUUID();
    @Autowired private MockMvc mvc;
    @MockitoBean private WebhookEndpointQueryService queries;
    @MockitoBean private TenantMembershipRepository memberships;
    @MockitoBean private TenantRepository tenants;
    @MockitoBean private WebhookEndpointRepository endpoints;
    @MockitoBean private WebhookUrlPolicy policy;
    @MockitoBean private WebhookSecretGenerator secrets;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void administratorCanEnableOrDisableOwnedEndpoint(boolean enabled) throws Exception {
        Tenant tenant = membership(TenantRole.ADMIN);
        WebhookEndpoint endpoint = new WebhookEndpoint(tenant, "Orders", "https://example.com/webhook",
                NOW, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        ReflectionTestUtils.setField(endpoint, "id", ENDPOINT_ID);
        endpoint.changeEnabled(!enabled, NOW);
        when(endpoints.findByIdAndTenant_Id(ENDPOINT_ID, TENANT_ID)).thenReturn(Optional.of(endpoint));

        mvc.perform(request(enabled).with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("id").value(ENDPOINT_ID.toString()))
                .andExpect(jsonPath("enabled").value(enabled))
                .andExpect(jsonPath("signingSecret").doesNotExist());
        assertEquals(enabled, endpoint.isEnabled());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verify(endpoints).findByIdAndTenant_Id(ENDPOINT_ID, TENANT_ID);
        verifyNoMoreInteractions(endpoints);
        verifyNoInteractions(tenants, secrets, policy, queries);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void viewerCannotChangeStateEvenWithForgedAdminHeader(boolean enabled) throws Exception {
        membership(TenantRole.VIEWER);
        mvc.perform(request(enabled).with(csrf()).header("X-Tenant-ID", TENANT_ID)
                        .header("X-Tenant-Role", "ADMIN"))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoInteractions(endpoints, tenants, secrets, policy, queries);
    }

    @Test
    void nonmemberIsDeniedBeforeEndpointLookup() throws Exception {
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
        mvc.perform(request(false).with(csrf()).header("X-Tenant-ID", TENANT_ID)
                        .header("X-User-ID", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoInteractions(endpoints, tenants, secrets, policy, queries);
    }

    @Test
    void foreignOrMissingEndpointReturns404AfterAdministratorCheck() throws Exception {
        membership(TenantRole.ADMIN);
        when(endpoints.findByIdAndTenant_Id(ENDPOINT_ID, TENANT_ID)).thenReturn(Optional.empty());
        mvc.perform(request(false).with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
        verify(endpoints).findByIdAndTenant_Id(ENDPOINT_ID, TENANT_ID);
        verifyNoMoreInteractions(endpoints);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void missingOrInvalidTenantHeaderIsRejectedBeforeLookup(String header) throws Exception {
        var request = request(false).with(csrf());
        if (!header.isEmpty()) request.header("X-Tenant-ID", header);
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(memberships, endpoints);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrInvalidCsrfIsRejectedBeforeLookup(boolean invalidToken) throws Exception {
        var request = request(false).header("X-Tenant-ID", TENANT_ID);
        if (invalidToken) request.with(csrf().useInvalidToken());
        mvc.perform(request).andExpect(status().isForbidden());
        verifyNoInteractions(memberships, endpoints);
    }

    @Test
    void anonymousRequestWithValidCsrfStillRequiresAuthentication() throws Exception {
        mvc.perform(request(false).with(anonymous()).with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(memberships, endpoints);
    }

    private MockHttpServletRequestBuilder request(boolean enabled) {
        return patch("/api/endpoints/{id}/enabled", ENDPOINT_ID).with(user(new AppUserDetails(account())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":%s}".formatted(enabled));
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
