package com.wenghong.notifyflow.delivery.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.delivery.application.DeliveryReplayService;
import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryAttempt;
import com.wenghong.notifyflow.delivery.persistence.DeliveryAttemptRepository;
import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.event.domain.Event;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.configuration.SecurityConfiguration;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantMembership;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.TenantMembershipRepository;
import com.wenghong.notifyflow.identity.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

@WebMvcTest(DeliveryReplayController.class)
@Import({SecurityConfiguration.class, TenantAccessService.class, DeliveryReplayService.class,
        TenantDeliveryReplayHttpTests.Transactions.class})
class TenantDeliveryReplayHttpTests {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    @Autowired private MockMvc mvc;
    @MockitoBean private TenantMembershipRepository memberships;
    @MockitoBean private DeliveryRepository deliveries;
    @MockitoBean private DeliveryAttemptRepository attempts;
    @MockitoBean private PlatformTransactionManager transactionManager;

    @BeforeEach
    void configureTransactionStatus() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Test
    void administratorReplaysOwnedFailedDeliveryUsingAuthenticatedUserId() throws Exception {
        Tenant tenant = membership(TenantRole.ADMIN);
        Delivery delivery = delivery(tenant);
        delivery.tryStartProcessing();
        delivery.markFailed();
        when(deliveries.findByIdAndTenantIdForUpdate(DELIVERY_ID, TENANT_ID)).thenReturn(Optional.of(delivery));
        DeliveryAttempt previous = new DeliveryAttempt(delivery, 5, NOW);
        when(attempts.findFirstByDelivery_IdOrderByAttemptNumberDesc(DELIVERY_ID)).thenReturn(Optional.of(previous));

        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("id").value(DELIVERY_ID.toString()))
                .andExpect(jsonPath("status").value("PENDING"));
        assertEquals(6, delivery.getRetryCycleStartAttempt());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verify(deliveries).findByIdAndTenantIdForUpdate(DELIVERY_ID, TENANT_ID);
        verifyNoMoreInteractions(deliveries);
    }

    @Test
    void viewerCannotReplayOrStartTransactionEvenWithForgedAdminHeader() throws Exception {
        membership(TenantRole.VIEWER);
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID).header("X-Tenant-Role", "ADMIN"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(deliveries, attempts, transactionManager);
    }

    @Test
    void nonmemberCannotBorrowIdentityFromAHeader() throws Exception {
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID).header("X-User-ID", DELIVERY_ID))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoInteractions(deliveries, attempts, transactionManager);
    }

    @Test
    void foreignOrMissingDeliveryReturns404WithoutReadingAttemptHistory() throws Exception {
        membership(TenantRole.ADMIN);
        when(deliveries.findByIdAndTenantIdForUpdate(DELIVERY_ID, TENANT_ID)).thenReturn(Optional.empty());
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
        verify(deliveries).findByIdAndTenantIdForUpdate(DELIVERY_ID, TENANT_ID);
        verifyNoInteractions(attempts);
    }

    @Test
    void ownedDeliveryThatIsNotFailedStillReturns409() throws Exception {
        Delivery delivery = delivery(membership(TenantRole.ADMIN));
        when(deliveries.findByIdAndTenantIdForUpdate(DELIVERY_ID, TENANT_ID)).thenReturn(Optional.of(delivery));
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isConflict());
        verifyNoInteractions(attempts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void missingOrMalformedTenantHeaderIsRejectedBeforeLookup(String header) throws Exception {
        var request = request().with(csrf());
        if (!header.isEmpty()) request.header("X-Tenant-ID", header);
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(memberships, deliveries, attempts, transactionManager);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrInvalidCsrfIsRejectedBeforeLookup(boolean invalidToken) throws Exception {
        var request = request().header("X-Tenant-ID", TENANT_ID);
        if (invalidToken) request.with(csrf().useInvalidToken());
        mvc.perform(request).andExpect(status().isForbidden());
        verifyNoInteractions(memberships, deliveries, attempts, transactionManager);
    }

    @Test
    void anonymousClientCannotReplayEvenWithValidCsrf() throws Exception {
        mvc.perform(request().with(anonymous()).with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(memberships, deliveries, attempts, transactionManager);
    }

    private MockHttpServletRequestBuilder request() {
        return post("/api/deliveries/{id}/replay", DELIVERY_ID).with(user(new AppUserDetails(account())));
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

    private Delivery delivery(Tenant tenant) {
        Event event = new Event(tenant, "order.confirmed", JsonMapper.builder().build().readTree("{}"), "test-key", NOW);
        WebhookEndpoint endpoint = new WebhookEndpoint(tenant, "Orders", "https://example.com/webhook",
                NOW, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        Delivery delivery = new Delivery(event, endpoint, NOW);
        ReflectionTestUtils.setField(delivery, "id", DELIVERY_ID);
        return delivery;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Transactions {
        @Bean
        TransactionTemplate transactions(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
    }
}
