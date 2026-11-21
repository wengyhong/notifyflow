package com.wenghong.notifyflow.endpoint.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.application.WebhookEndpointCache;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointQueryService;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointService;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointSnapshot;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.configuration.SecurityConfiguration;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantMembership;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.TenantMembershipRepository;
import com.wenghong.notifyflow.identity.security.AppUserDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real membership and query services enforce both checks across the HTTP boundary.
@WebMvcTest(WebhookEndpointController.class)
@Import({SecurityConfiguration.class, TenantAccessService.class, WebhookEndpointQueryService.class})
class TenantEndpointReadHttpTests {
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID ENDPOINT_ID = UUID.randomUUID();
    @Autowired private MockMvc mvc;
    @MockitoBean private WebhookEndpointService endpoints;
    @MockitoBean private WebhookEndpointRepository repository;
    @MockitoBean private WebhookEndpointCache cache;
    @MockitoBean private TenantMembershipRepository memberships;

    @ParameterizedTest
    @EnumSource(TenantRole.class)
    void verifiedMemberCanReadOwnCachedEndpoint(TenantRole role) throws Exception {
        membership(role);
        when(cache.findById(ENDPOINT_ID)).thenReturn(Optional.of(snapshot(TENANT_ID)));

        mvc.perform(request().header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("id").value(ENDPOINT_ID.toString()))
                .andExpect(jsonPath("name").value("Orders"))
                .andExpect(jsonPath("signingSecret").doesNotExist());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoInteractions(repository, endpoints);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void foreignOrUnownedCacheEntryDoesNotLeakThroughHttp(boolean missingOwner) throws Exception {
        membership(TenantRole.VIEWER);
        when(cache.findById(ENDPOINT_ID)).thenReturn(Optional.of(snapshot(missingOwner ? null : UUID.randomUUID())));
        when(repository.findByIdAndTenant_Id(ENDPOINT_ID, TENANT_ID)).thenReturn(Optional.empty());

        mvc.perform(request().header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
        verify(repository).findByIdAndTenant_Id(ENDPOINT_ID, TENANT_ID);
        verify(cache, never()).put(any());
    }

    @Test
    void nonmemberIsDeniedBeforeEvenReadingTheCache() throws Exception {
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
        mvc.perform(request().header("X-Tenant-ID", TENANT_ID)
                        .header("X-User-ID", UUID.randomUUID()).header("X-Tenant-Role", "ADMIN"))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoInteractions(cache, repository, endpoints);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void missingOrInvalidTenantHeaderIsRejectedBeforeLookup(String header) throws Exception {
        var request = request();
        if (!header.isEmpty()) request.header("X-Tenant-ID", header);
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(memberships, cache, repository, endpoints);
    }

    private MockHttpServletRequestBuilder request() {
        return get("/api/endpoints/{id}", ENDPOINT_ID).with(user(new AppUserDetails(account())));
    }

    private AppUser account() {
        AppUser account = new AppUser("reader@example.com", "unused-test-hash", NOW);
        ReflectionTestUtils.setField(account, "id", USER_ID);
        return account;
    }

    private void membership(TenantRole role) {
        Tenant tenant = new Tenant("Company A", NOW);
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID))
                .thenReturn(Optional.of(new TenantMembership(tenant, account(), role, NOW)));
    }

    private WebhookEndpointSnapshot snapshot(UUID owner) {
        return new WebhookEndpointSnapshot(ENDPOINT_ID, owner, "Orders", "https://example.com/webhook", true, NOW, NOW);
    }
}
