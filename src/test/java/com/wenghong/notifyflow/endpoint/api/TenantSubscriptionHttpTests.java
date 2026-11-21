package com.wenghong.notifyflow.endpoint.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.application.WebhookSubscriptionService;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.domain.WebhookSubscription;
import com.wenghong.notifyflow.endpoint.persistence.WebhookEndpointRepository;
import com.wenghong.notifyflow.endpoint.persistence.WebhookSubscriptionRepository;
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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookSubscriptionController.class)
@Import({SecurityConfiguration.class, TenantAccessService.class, WebhookSubscriptionService.class})
class TenantSubscriptionHttpTests {
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID ENDPOINT_ID = UUID.randomUUID();
    @Autowired private MockMvc mvc;
    @MockitoBean private TenantMembershipRepository memberships;
    @MockitoBean private WebhookEndpointRepository endpoints;
    @MockitoBean private WebhookSubscriptionRepository subscriptions;

    @Test
    void administratorCreatesSubscriptionForVerifiedTenantsEndpoint() throws Exception {
        Tenant tenant = membership(TenantRole.ADMIN);
        WebhookEndpoint endpoint = new WebhookEndpoint(tenant, "Orders", "https://example.com/webhook",
                NOW, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        ReflectionTestUtils.setField(endpoint, "id", ENDPOINT_ID);
        when(endpoints.findByIdAndTenant_Id(ENDPOINT_ID, TENANT_ID)).thenReturn(Optional.of(endpoint));
        UUID subscriptionId = UUID.randomUUID();
        when(subscriptions.saveAndFlush(any(WebhookSubscription.class))).thenAnswer(call -> {
            WebhookSubscription subscription = call.getArgument(0);
            ReflectionTestUtils.setField(subscription, "id", subscriptionId);
            return subscription;
        });

        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("id").value(subscriptionId.toString()))
                .andExpect(jsonPath("endpointId").value(ENDPOINT_ID.toString()))
                .andExpect(jsonPath("eventType").value("order.confirmed"));
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verify(endpoints).findByIdAndTenant_Id(ENDPOINT_ID, TENANT_ID);
        var saved = ArgumentCaptor.forClass(WebhookSubscription.class);
        verify(subscriptions).saveAndFlush(saved.capture());
        assertSame(endpoint, saved.getValue().getEndpoint());
    }

    @Test
    void viewerCannotSubscribeEvenWithForgedAdminHeader() throws Exception {
        membership(TenantRole.VIEWER);
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID).header("X-Tenant-Role", "ADMIN"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(endpoints, subscriptions);
    }

    @Test
    void nonmemberCannotBorrowAnotherUsersIdentity() throws Exception {
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID)
                        .header("X-User-ID", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoInteractions(endpoints, subscriptions);
    }

    @Test
    void foreignOrMissingEndpointReturns404WithoutWritingSubscription() throws Exception {
        membership(TenantRole.ADMIN);
        when(endpoints.findByIdAndTenant_Id(ENDPOINT_ID, TENANT_ID)).thenReturn(Optional.empty());
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
        verify(endpoints).findByIdAndTenant_Id(ENDPOINT_ID, TENANT_ID);
        verifyNoMoreInteractions(endpoints);
        verifyNoInteractions(subscriptions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void missingOrInvalidTenantHeaderIsRejectedBeforeMembershipLookup(String header) throws Exception {
        var request = request().with(csrf());
        if (!header.isEmpty()) request.header("X-Tenant-ID", header);
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(memberships, endpoints, subscriptions);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrInvalidCsrfIsRejectedBeforeMembershipLookup(boolean invalidToken) throws Exception {
        var request = request().header("X-Tenant-ID", TENANT_ID);
        if (invalidToken) request.with(csrf().useInvalidToken());
        mvc.perform(request).andExpect(status().isForbidden());
        verifyNoInteractions(memberships, endpoints, subscriptions);
    }

    @Test
    void anonymousClientWithValidCsrfCannotSubscribe() throws Exception {
        mvc.perform(request().with(anonymous()).with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(memberships, endpoints, subscriptions);
    }

    private MockHttpServletRequestBuilder request() {
        return post("/api/subscriptions/{id}", ENDPOINT_ID).with(user(new AppUserDetails(account())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"eventType\":\"order.confirmed\"}");
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
