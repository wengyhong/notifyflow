package com.wenghong.notifyflow.endpoint.api;

import java.time.Instant;
import java.util.List;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookEndpointController.class)
@Import({SecurityConfiguration.class, TenantAccessService.class, WebhookEndpointService.class})
class TenantEndpointListHttpTests {
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    @Autowired private MockMvc mvc;
    @MockitoBean private WebhookEndpointQueryService queries;
    @MockitoBean private TenantMembershipRepository memberships;
    @MockitoBean private TenantRepository tenants;
    @MockitoBean private WebhookEndpointRepository endpoints;
    @MockitoBean private WebhookUrlPolicy policy;
    @MockitoBean private WebhookSecretGenerator secrets;

    @ParameterizedTest
    @EnumSource(TenantRole.class)
    void bothMemberRolesCanListTheirTenantWithPagination(TenantRole role) throws Exception {
        Tenant tenant = membership(role);
        var pageable = PageRequest.of(1, 2, Sort.by("name").ascending());
        WebhookEndpoint endpoint = new WebhookEndpoint(tenant, "Orders", "https://example.com/webhook",
                NOW, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        UUID endpointId = UUID.randomUUID();
        ReflectionTestUtils.setField(endpoint, "id", endpointId);
        when(endpoints.findAllByTenant_Id(TENANT_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(endpoint), pageable, 3));

        mvc.perform(request().header("X-Tenant-ID", TENANT_ID)
                        .param("page", "1").param("size", "2").param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("content.length()").value(1))
                .andExpect(jsonPath("content[0].id").value(endpointId.toString()))
                .andExpect(jsonPath("content[0].signingSecret").doesNotExist())
                .andExpect(jsonPath("page").value(1))
                .andExpect(jsonPath("size").value(2))
                .andExpect(jsonPath("totalElements").value(3))
                .andExpect(jsonPath("totalPages").value(2));
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verify(endpoints).findAllByTenant_Id(TENANT_ID, pageable);
        verifyNoMoreInteractions(endpoints);
        verifyNoInteractions(tenants, policy, secrets, queries);
    }

    @Test
    void nonmemberCannotListUsingForgedUserOrRoleHeaders() throws Exception {
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
        mvc.perform(request().header("X-Tenant-ID", TENANT_ID)
                        .header("X-User-ID", UUID.randomUUID()).header("X-Tenant-Role", "ADMIN"))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoInteractions(endpoints, queries);
    }

    @Test
    void membershipInOneTenantDoesNotAuthorizeListingAnother() throws Exception {
        membership(TenantRole.ADMIN);
        UUID otherTenant = UUID.randomUUID();
        when(memberships.findByTenant_IdAndUser_Id(otherTenant, USER_ID)).thenReturn(Optional.empty());
        mvc.perform(request().header("X-Tenant-ID", otherTenant)).andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(otherTenant, USER_ID);
        verifyNoInteractions(endpoints, queries);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void missingOrInvalidTenantHeaderIsRejectedBeforeMembershipLookup(String header) throws Exception {
        var request = request();
        if (!header.isEmpty()) request.header("X-Tenant-ID", header);
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(memberships, endpoints, queries);
    }

    @Test
    void anonymousClientCannotListEndpoints() throws Exception {
        mvc.perform(request().with(anonymous()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(memberships, endpoints, queries);
    }

    @Test
    void verifiedMemberOfEmptyTenantReceivesAnEmptyPage() throws Exception {
        membership(TenantRole.VIEWER);
        var pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        when(endpoints.findAllByTenant_Id(TENANT_ID, pageable)).thenReturn(Page.empty(pageable));
        mvc.perform(request().header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("content").isEmpty())
                .andExpect(jsonPath("totalElements").value(0))
                .andExpect(jsonPath("totalPages").value(0));
        verify(endpoints).findAllByTenant_Id(TENANT_ID, pageable);
    }

    private MockHttpServletRequestBuilder request() {
        return get("/api/endpoints").with(user(new AppUserDetails(account())));
    }

    private AppUser account() {
        AppUser account = new AppUser("reader@example.com", "unused-test-hash", NOW);
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
