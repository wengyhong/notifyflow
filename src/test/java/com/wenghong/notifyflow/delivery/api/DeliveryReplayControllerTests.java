package com.wenghong.notifyflow.delivery.api;

import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.delivery.application.DeliveryReplayNotAllowedException;
import com.wenghong.notifyflow.delivery.application.DeliveryReplayService;
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
        mockMvc.perform(post("/api/deliveries/{id}/replay", UUID.randomUUID()).with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void replayWithoutCsrfIsRejectedBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/deliveries/{id}/replay", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void replayWithInvalidCsrfIsRejectedBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/deliveries/{id}/replay", UUID.randomUUID()).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
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
        when(service.replay(id)).thenReturn(Optional.of(delivery));

        mockMvc.perform(post("/api/deliveries/{id}/replay", id).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(service).replay(id);
        verifyNoMoreInteractions(service);
    }

    @Test
    void missingDeliveryReturnsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.replay(id)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/deliveries/{id}/replay", id).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        verify(service).replay(id);
        verifyNoMoreInteractions(service);
    }

    @Test
    void disallowedReplayReturnsConflictProblemDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.replay(id)).thenThrow(new DeliveryReplayNotAllowedException());

        mockMvc.perform(post("/api/deliveries/{id}/replay", id).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Only failed deliveries can be replayed"))
                .andExpect(jsonPath("$.instance").value("/api/deliveries/" + id + "/replay"));

        verify(service).replay(id);
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "a8a1d32a-a014-47bb-bf82-f31ea55e4acg"})
    void malformedDeliveryIdReturnsBadRequestWithoutCallingService(String id) throws Exception {
        mockMvc.perform(post("/api/deliveries/{id}/replay", id).with(csrf()))
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
