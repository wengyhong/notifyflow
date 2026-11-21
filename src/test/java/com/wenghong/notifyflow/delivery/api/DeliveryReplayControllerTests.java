package com.wenghong.notifyflow.delivery.api;

import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.delivery.application.DeliveryReplayNotAllowedException;
import com.wenghong.notifyflow.delivery.application.DeliveryReplayService;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import java.time.Instant;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import com.wenghong.notifyflow.identity.configuration.SecurityConfiguration;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeliveryReplayController.class)
@Import(SecurityConfiguration.class)
@WithMockUser(roles = {})
class DeliveryReplayControllerTests {

    @Test
    @WithAnonymousUser
    void anonymousReplayIsRejectedBeforeCallingService() throws Exception {
        mockMvc.perform(replayRequest(UUID.randomUUID()).with(anonymous()).with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void replayWithoutCsrfIsRejectedBeforeCallingService() throws Exception {
        mockMvc.perform(replayRequest(UUID.randomUUID()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void replayWithInvalidCsrfIsRejectedBeforeCallingService() throws Exception {
        mockMvc.perform(replayRequest(UUID.randomUUID()).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final TenantAccess ACCESS = new TenantAccess(TENANT_ID, TenantRole.ADMIN);
    @MockitoBean private TenantAccessService tenantAccess;

    @BeforeEach
    void verifiedMembership() {
        when(tenantAccess.requireAccess(TENANT_ID, USER_ID)).thenReturn(ACCESS);
    }

    private MockHttpServletRequestBuilder replayRequest(Object id) {
        AppUser account = new AppUser("admin@example.com", "unused-test-hash", Instant.parse("2026-11-20T00:00:00Z"));
        ReflectionTestUtils.setField(account, "id", USER_ID);
        return post("/api/deliveries/{id}/replay", id).with(user(new AppUserDetails(account)))
                .header("X-Tenant-ID", TENANT_ID);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeliveryReplayService service;

    @Test
    void replayAcceptsRequestWithoutBodyAndReturnsDeliverySummary() throws Exception {
        UUID id = UUID.randomUUID();
        Delivery delivery = mock(Delivery.class);
        when(delivery.getId()).thenReturn(id);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.PENDING);
        when(service.replay(ACCESS, id)).thenReturn(Optional.of(delivery));

        mockMvc.perform(replayRequest(id).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(service).replay(ACCESS, id);
        verifyNoMoreInteractions(service);
    }

    @Test
    void missingDeliveryReturnsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.replay(ACCESS, id)).thenReturn(Optional.empty());

        mockMvc.perform(replayRequest(id).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        verify(service).replay(ACCESS, id);
        verifyNoMoreInteractions(service);
    }

    @Test
    void disallowedReplayReturnsConflictProblemDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.replay(ACCESS, id)).thenThrow(new DeliveryReplayNotAllowedException());

        mockMvc.perform(replayRequest(id).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Only failed deliveries can be replayed"))
                .andExpect(jsonPath("$.instance").value("/api/deliveries/" + id + "/replay"));

        verify(service).replay(ACCESS, id);
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "a8a1d32a-a014-47bb-bf82-f31ea55e4acg"})
    void malformedDeliveryIdReturnsBadRequestWithoutCallingService(String id) throws Exception {
        mockMvc.perform(replayRequest(id).with(csrf()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void getCannotTriggerReplay() throws Exception {
        mockMvc.perform(get("/api/deliveries/{id}/replay", UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(service);
    }
}
