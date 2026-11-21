package com.wenghong.notifyflow.event.api;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.delivery.persistence.DeliveryRepository;
import com.wenghong.notifyflow.endpoint.persistence.WebhookSubscriptionRepository;
import com.wenghong.notifyflow.event.application.EventIdempotencyCache;
import com.wenghong.notifyflow.event.application.EventIngestionService;
import com.wenghong.notifyflow.event.application.EventSnapshot;
import com.wenghong.notifyflow.event.domain.Event;
import com.wenghong.notifyflow.event.persistence.EventRepository;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventController.class)
@Import({SecurityConfiguration.class, TenantAccessService.class, EventIngestionService.class})
class TenantEventIngestionHttpTests {
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final String KEY = "request-128";
    private static final String BODY = "{\"type\":\"order.confirmed\",\"payload\":{\"orderId\":128}}";
    @Autowired private MockMvc mvc;
    @MockitoBean private TenantMembershipRepository memberships;
    @MockitoBean private TenantRepository tenants;
    @MockitoBean private EventRepository events;
    @MockitoBean private WebhookSubscriptionRepository subscriptions;
    @MockitoBean private DeliveryRepository deliveries;
    @MockitoBean private EventIdempotencyCache cache;
    @MockitoBean private TransactionTemplate transactions;
    @MockitoBean private Clock clock;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void administratorCanRetryOwnedEventFromCacheOrDatabase(boolean cached) throws Exception {
        Event stored = storedEvent(membership(TenantRole.ADMIN));
        when(cache.findByKey(TENANT_ID, KEY)).thenReturn(cached ? Optional.of(EventSnapshot.from(stored)) : Optional.empty());
        if (!cached) when(events.findByTenant_IdAndIdempotencyKey(TENANT_ID, KEY)).thenReturn(Optional.of(stored));

        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("id").value(EVENT_ID.toString()))
                .andExpect(jsonPath("type").value("order.confirmed"));
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verify(cache).findByKey(TENANT_ID, KEY);
        if (cached) verifyNoInteractions(events);
        else verify(events).findByTenant_IdAndIdempotencyKey(TENANT_ID, KEY);
        verifyNoInteractions(subscriptions, deliveries, transactions, tenants, clock);
    }

    @Test
    void viewerCannotIngestEvenWhenResultIsCachedAndAdminHeaderIsForged() throws Exception {
        Event stored = storedEvent(membership(TenantRole.VIEWER));
        when(cache.findByKey(TENANT_ID, KEY)).thenReturn(Optional.of(EventSnapshot.from(stored)));
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID).header("X-Tenant-Role", "ADMIN"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(cache, events, subscriptions, deliveries, transactions, tenants, clock);
    }

    @Test
    void nonmemberCannotIngestWithForgedUserHeader() throws Exception {
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID).header("X-User-ID", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoInteractions(cache, events, subscriptions, deliveries, transactions);
    }

    @Test
    void sameTenantConflictingCachedContentStillReturns409() throws Exception {
        Event stored = storedEvent(membership(TenantRole.ADMIN));
        when(cache.findByKey(TENANT_ID, KEY)).thenReturn(Optional.of(EventSnapshot.from(stored)));
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID)
                        .content("{\"type\":\"order.confirmed\",\"payload\":{\"orderId\":999}}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(events, subscriptions, deliveries, transactions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void missingOrMalformedTenantHeaderIsRejectedBeforeLookup(String header) throws Exception {
        var request = request().with(csrf());
        if (!header.isEmpty()) request.header("X-Tenant-ID", header);
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(memberships, cache, events, subscriptions, deliveries, transactions);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrInvalidCsrfIsRejectedBeforeLookup(boolean invalidToken) throws Exception {
        var request = request().header("X-Tenant-ID", TENANT_ID);
        if (invalidToken) request.with(csrf().useInvalidToken());
        mvc.perform(request).andExpect(status().isForbidden());
        verifyNoInteractions(memberships, cache, events, subscriptions, deliveries, transactions);
    }

    @Test
    void anonymousClientWithValidCsrfCannotIngest() throws Exception {
        mvc.perform(request().with(anonymous()).with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(memberships, cache, events, subscriptions, deliveries, transactions);
    }

    private MockHttpServletRequestBuilder request() {
        return post("/api/events").with(user(new AppUserDetails(account())))
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(BODY);
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

    private Event storedEvent(Tenant tenant) {
        Event stored = new Event(tenant, "order.confirmed", JsonMapper.builder().build().readTree("{\"orderId\":128}"), KEY, NOW);
        ReflectionTestUtils.setField(stored, "id", EVENT_ID);
        return stored;
    }
}
