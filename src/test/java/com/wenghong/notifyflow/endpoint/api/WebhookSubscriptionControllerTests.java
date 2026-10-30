package com.wenghong.notifyflow.endpoint.api;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.wenghong.notifyflow.endpoint.application.WebhookSubscriptionService;
import com.wenghong.notifyflow.endpoint.application.DuplicateWebhookSubscriptionException;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.endpoint.domain.WebhookSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookSubscriptionController.class)
class WebhookSubscriptionControllerTests {

    private static final UUID ENDPOINT_ID =
            UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
    private static final UUID SUBSCRIPTION_ID =
            UUID.fromString("e292322e-31ce-4892-bf9d-33e3eba2c5d7");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WebhookSubscriptionService service;

    @Test
    void createReturnsConflictWhenSubscriptionAlreadyExists() throws Exception {
        RuntimeException databaseCause = new RuntimeException("Internal database diagnostic");
        when(service.create(ENDPOINT_ID, "order.confirmed"))
                .thenThrow(new DuplicateWebhookSubscriptionException(databaseCause));

        mockMvc.perform(post("/api/subscriptions/{id}", ENDPOINT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"order.confirmed\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail")
                        .value("The endpoint is already subscribed to this event type."))
                .andExpect(jsonPath("$.cause").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());

        verify(service).create(ENDPOINT_ID, "order.confirmed");
    }

    @ParameterizedTest
    @MethodSource("validEventTypes")
    void createReturnsCreatedWithSubscriptionResponse(String eventType) throws Exception {
        WebhookEndpoint endpoint = mock(WebhookEndpoint.class);
        WebhookSubscription subscription = mock(WebhookSubscription.class);
        when(endpoint.getId()).thenReturn(ENDPOINT_ID);
        when(subscription.getId()).thenReturn(SUBSCRIPTION_ID);
        when(subscription.getEndpoint()).thenReturn(endpoint);
        when(subscription.getEventType()).thenReturn(eventType);
        when(service.create(ENDPOINT_ID, eventType)).thenReturn(Optional.of(subscription));

        mockMvc.perform(post("/api/subscriptions/{id}", ENDPOINT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"%s\"}".formatted(eventType)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(SUBSCRIPTION_ID.toString()))
                .andExpect(jsonPath("$.endpointId").value(ENDPOINT_ID.toString()))
                .andExpect(jsonPath("$.eventType").value(eventType));

        verify(service).create(ENDPOINT_ID, eventType);
    }

    private static Stream<String> validEventTypes() {
        return Stream.of("order.confirmed", "Order.Confirmed", "x".repeat(100));
    }

    @Test
    void createReturnsNotFoundWhenEndpointIsMissing() throws Exception {
        when(service.create(ENDPOINT_ID, "order.confirmed")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/subscriptions/{id}", ENDPOINT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"order.confirmed\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        verify(service).create(ENDPOINT_ID, "order.confirmed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"eventType\":null}",
            "{\"eventType\":\"\"}",
            "{\"eventType\":\"   \"}"
    })
    void createRejectsMissingNullOrBlankEventType(String requestBody) throws Exception {
        mockMvc.perform(post("/api/subscriptions/{id}", ENDPOINT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void createRejectsEventTypeLongerThan100Characters() throws Exception {
        String eventType = "x".repeat(101);

        mockMvc.perform(post("/api/subscriptions/{id}", ENDPOINT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"%s\"}".formatted(eventType)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "", "null", "{\"eventType\":{}}"})
    void createRejectsMalformedOrMissingBody(String requestBody) throws Exception {
        mockMvc.perform(post("/api/subscriptions/{id}", ENDPOINT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void createRejectsMalformedEndpointId() throws Exception {
        mockMvc.perform(post("/api/subscriptions/{id}", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"order.confirmed\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void createRejectsUnsupportedContentType() throws Exception {
        mockMvc.perform(post("/api/subscriptions/{id}", ENDPOINT_ID)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("order.confirmed"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(service);
    }
}
