package com.wenghong.notifyflow.endpoint.api;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.application.WebhookSecretGenerator;
import com.wenghong.notifyflow.endpoint.application.WebhookSecretRotationService;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookSecretRotationController.class)
@Import({SecurityConfiguration.class, TenantAccessService.class, WebhookSecretRotationService.class})
class TenantSecretRotationHttpTests {
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID ENDPOINT_ID = UUID.randomUUID();
    private static final String ORIGINAL = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String NEXT = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    @Autowired private MockMvc mvc;
    @MockitoBean private TenantMembershipRepository memberships;
    @MockitoBean private WebhookEndpointRepository endpoints;
    @MockitoBean private WebhookSecretGenerator generator;
    @MockitoBean private Clock clock;

    @Test
    void administratorRotatesOnlyTheVerifiedTenantsEndpoint() throws Exception {
        WebhookEndpoint endpoint = endpoint(membership(TenantRole.ADMIN));
        when(endpoints.findByIdAndTenantIdForUpdate(ENDPOINT_ID, TENANT_ID)).thenReturn(Optional.of(endpoint));
        when(clock.instant()).thenReturn(NOW);
        when(generator.generate()).thenReturn(NEXT);

        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("id").value(ENDPOINT_ID.toString()))
                .andExpect(jsonPath("signingSecret").value(NEXT))
                .andExpect(jsonPath("previousSigningSecretExpiresAt").value(NOW.plusSeconds(3600).toString()))
                .andExpect(jsonPath("previousSigningSecret").doesNotExist());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verify(endpoints).findByIdAndTenantIdForUpdate(ENDPOINT_ID, TENANT_ID);
        verifyNoMoreInteractions(endpoints);
        verify(generator).generate();
    }

    @Test
    void viewerCannotRotateEvenWithForgedAdminHeader() throws Exception {
        membership(TenantRole.VIEWER);
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID).header("X-Tenant-Role", "ADMIN"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(endpoints, generator, clock);
    }

    @Test
    void nonmemberCannotBorrowIdentityToRotate() throws Exception {
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID)
                        .header("X-User-ID", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoInteractions(endpoints, generator, clock);
    }

    @Test
    void foreignOrMissingEndpointReturns404WithoutGeneratingASecret() throws Exception {
        membership(TenantRole.ADMIN);
        when(endpoints.findByIdAndTenantIdForUpdate(ENDPOINT_ID, TENANT_ID)).thenReturn(Optional.empty());
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
        verify(endpoints).findByIdAndTenantIdForUpdate(ENDPOINT_ID, TENANT_ID);
        verifyNoMoreInteractions(endpoints);
        verifyNoInteractions(generator, clock);
    }

    @Test
    void activeOverlapStillReturns409WithoutGeneratingAnotherSecret() throws Exception {
        WebhookEndpoint endpoint = endpoint(membership(TenantRole.ADMIN));
        endpoint.rotateSigningSecret(NEXT, NOW.minusSeconds(60), NOW.plusSeconds(3540));
        when(endpoints.findByIdAndTenantIdForUpdate(ENDPOINT_ID, TENANT_ID)).thenReturn(Optional.of(endpoint));
        when(clock.instant()).thenReturn(NOW);
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isConflict()).andExpect(jsonPath("status").value(409));
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void missingOrInvalidTenantHeaderIsRejectedBeforeLookup(String header) throws Exception {
        var request = request().with(csrf());
        if (!header.isEmpty()) request.header("X-Tenant-ID", header);
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(memberships, endpoints, generator, clock);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrInvalidCsrfIsRejectedBeforeMembershipLookup(boolean invalidToken) throws Exception {
        var request = request().header("X-Tenant-ID", TENANT_ID);
        if (invalidToken) request.with(csrf().useInvalidToken());
        mvc.perform(request).andExpect(status().isForbidden());
        verifyNoInteractions(memberships, endpoints, generator, clock);
    }

    @Test
    void anonymousRequestWithValidCsrfStillRequiresAuthentication() throws Exception {
        mvc.perform(request().with(anonymous()).with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(memberships, endpoints, generator, clock);
    }

    private MockHttpServletRequestBuilder request() {
        return post("/api/endpoints/{id}/signing-secret/rotate", ENDPOINT_ID)
                .with(user(new AppUserDetails(account())));
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

    private WebhookEndpoint endpoint(Tenant tenant) {
        var endpoint = new WebhookEndpoint(tenant, "Orders", "https://example.com/webhook", NOW.minusSeconds(7200), ORIGINAL);
        ReflectionTestUtils.setField(endpoint, "id", ENDPOINT_ID);
        return endpoint;
    }
}
