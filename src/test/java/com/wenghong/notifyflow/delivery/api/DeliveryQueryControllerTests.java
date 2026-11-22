package com.wenghong.notifyflow.delivery.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.wenghong.notifyflow.delivery.application.DeliveryQueryService;
import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryAttempt;
import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DeliveryQueryController.class)
@Import({SecurityConfiguration.class, TenantAccessService.class, DeliveryQueryService.class})
class DeliveryQueryControllerTests {
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID ENDPOINT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    @Autowired private MockMvc mvc;
    @MockitoBean private TenantMembershipRepository memberships;
    @MockitoBean private DeliveryRepository deliveries;
    @MockitoBean private DeliveryAttemptRepository attempts;

    @BeforeEach
    void administratorMembership() {
        membership(TenantRole.ADMIN);
    }

    @ParameterizedTest
    @EnumSource(TenantRole.class)
    void bothRolesReadListDetailAndAttemptsWithNoSecretsOrCsrfRequirement(TenantRole role) throws Exception {
        membership(role);
        Delivery delivery = delivery();
        when(deliveries.findHistoryByTenantId(eq(TENANT_ID), isNull(), any(Pageable.class)))
                .thenAnswer(call -> new PageImpl<>(List.of(delivery), call.getArgument(2), 1));
        when(deliveries.findByIdAndTenantId(DELIVERY_ID, TENANT_ID)).thenReturn(Optional.of(delivery));
        DeliveryAttempt attempt = new DeliveryAttempt(delivery, 1, NOW);
        ReflectionTestUtils.setField(attempt, "id", UUID.randomUUID());
        attempt.completeWithResponse(503, NOW.plusSeconds(1));
        when(attempts.findHistoryByDeliveryIdAndTenantId(eq(DELIVERY_ID), eq(TENANT_ID), any(Pageable.class)))
                .thenAnswer(call -> new PageImpl<>(List.of(attempt), call.getArgument(2), 1));

        mvc.perform(request("/api/deliveries"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].*", hasSize(7)))
                .andExpect(jsonPath("$.content[0].id").value(DELIVERY_ID.toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
        mvc.perform(request("/api/deliveries/" + DELIVERY_ID))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.*", hasSize(7)))
                .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.endpointId").value(ENDPOINT_ID.toString()))
                .andExpect(jsonPath("$.status").value("RETRY_SCHEDULED"))
                .andExpect(jsonPath("$.nextAttemptAt").value(NOW.plusSeconds(60).toString()))
                .andExpect(jsonPath("$.retryCycleStartAttempt").value(1));
        mvc.perform(request("/api/deliveries/" + DELIVERY_ID + "/attempts"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.content[0].*", hasSize(7)))
                .andExpect(jsonPath("$.content[0].deliveryId").value(DELIVERY_ID.toString()))
                .andExpect(jsonPath("$.content[0].attemptNumber").value(1))
                .andExpect(jsonPath("$.content[0].responseStatus").value(503))
                .andExpect(jsonPath("$.content[0].errorMessage").isEmpty());
        var page = ArgumentCaptor.forClass(Pageable.class);
        verify(deliveries).findHistoryByTenantId(eq(TENANT_ID), isNull(), page.capture());
        assertEquals(Sort.by(Sort.Direction.DESC, "createdAt", "id"), page.getValue().getSort());
        verify(attempts).findHistoryByDeliveryIdAndTenantId(eq(DELIVERY_ID), eq(TENANT_ID), page.capture());
        assertEquals(Sort.by(Sort.Direction.DESC, "attemptNumber"), page.getValue().getSort());
        verify(memberships, times(3)).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
    }

    @Test
    void listPassesStatusAndExplicitPaginationAndReturnsEmptyPageMetadata() throws Exception {
        when(deliveries.findHistoryByTenantId(eq(TENANT_ID), eq(DeliveryStatus.FAILED), any(Pageable.class)))
                .thenAnswer(call -> Page.empty(call.getArgument(2)));
        mvc.perform(request("/api/deliveries").param("status", "FAILED").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(0));
        var page = ArgumentCaptor.forClass(Pageable.class);
        verify(deliveries).findHistoryByTenantId(eq(TENANT_ID), eq(DeliveryStatus.FAILED), page.capture());
        assertEquals(2, page.getValue().getPageNumber());
        assertEquals(5, page.getValue().getPageSize());
    }

    @Test
    void ownedDeliveryWithoutAttemptsReturnsEmptyPage() throws Exception {
        Delivery owned = delivery();
        when(deliveries.findByIdAndTenantId(DELIVERY_ID, TENANT_ID)).thenReturn(Optional.of(owned));
        when(attempts.findHistoryByDeliveryIdAndTenantId(eq(DELIVERY_ID), eq(TENANT_ID), any(Pageable.class)))
                .thenAnswer(call -> Page.empty(call.getArgument(2)));
        mvc.perform(request("/api/deliveries/" + DELIVERY_ID + "/attempts").param("page", "1").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/attempts"})
    void absentOrForeignDeliveryReturns404WithoutAttemptQuery(String suffix) throws Exception {
        mvc.perform(request("/api/deliveries/" + DELIVERY_ID + suffix))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
        verifyNoInteractions(attempts);
    }

    @ParameterizedTest
    @MethodSource("routes")
    void anonymousUserCannotReadAnyHistoryRoute(String route) throws Exception {
        mvc.perform(request(route).with(anonymous())).andExpect(status().isUnauthorized());
        verifyNoInteractions(memberships, deliveries, attempts);
    }

    @ParameterizedTest
    @MethodSource("routes")
    void nonmemberCannotReadAnyHistoryRoute(String route) throws Exception {
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
        mvc.perform(request(route)).andExpect(status().isForbidden());
        verifyNoInteractions(deliveries, attempts);
    }

    @ParameterizedTest
    @MethodSource("routes")
    void membershipInOneTenantCannotBeUsedForAnotherTenant(String route) throws Exception {
        var request = request(route);
        request.headers(headers -> headers.set("X-Tenant-ID", UUID.randomUUID().toString()));
        mvc.perform(request).andExpect(status().isForbidden());
        verifyNoInteractions(deliveries, attempts);
    }

    @ParameterizedTest
    @MethodSource("routes")
    void missingOrMalformedTenantHeaderIs400(String route) throws Exception {
        var missing = request(route);
        missing.headers(headers -> headers.remove("X-Tenant-ID"));
        mvc.perform(missing).andExpect(status().isBadRequest());
        var malformed = request(route);
        malformed.headers(headers -> headers.set("X-Tenant-ID", "invalid"));
        mvc.perform(malformed).andExpect(status().isBadRequest());
        verifyNoInteractions(memberships, deliveries, attempts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/deliveries/invalid", "/api/deliveries/invalid/attempts", "/api/deliveries?status=UNKNOWN"})
    void malformedDeliveryIdOrStatusIs400(String route) throws Exception {
        mvc.perform(request(route)).andExpect(status().isBadRequest());
        verifyNoInteractions(memberships, deliveries, attempts);
    }

    private void membership(TenantRole role) {
        Tenant tenant = new Tenant("Workspace", NOW);
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID))
                .thenReturn(Optional.of(new TenantMembership(tenant, account(), role, NOW)));
    }

    private AppUser account() {
        AppUser account = new AppUser("viewer@example.com", "unused-test-hash", NOW);
        ReflectionTestUtils.setField(account, "id", USER_ID);
        return account;
    }

    private MockHttpServletRequestBuilder request(String route) {
        return get(route).with(user(new AppUserDetails(account()))).header("X-Tenant-ID", TENANT_ID);
    }

    private Delivery delivery() {
        Delivery delivery = mock(Delivery.class);
        Event event = mock(Event.class);
        WebhookEndpoint endpoint = mock(WebhookEndpoint.class);
        when(delivery.getId()).thenReturn(DELIVERY_ID);
        when(delivery.getEvent()).thenReturn(event);
        when(delivery.getEndpoint()).thenReturn(endpoint);
        when(event.getId()).thenReturn(EVENT_ID);
        when(endpoint.getId()).thenReturn(ENDPOINT_ID);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.RETRY_SCHEDULED);
        when(delivery.getCreatedAt()).thenReturn(NOW);
        when(delivery.getNextAttemptAt()).thenReturn(NOW.plusSeconds(60));
        when(delivery.getRetryCycleStartAttempt()).thenReturn(1);
        return delivery;
    }

    private static Stream<String> routes() {
        return Stream.of("/api/deliveries", "/api/deliveries/" + DELIVERY_ID,
                "/api/deliveries/" + DELIVERY_ID + "/attempts");
    }
}
