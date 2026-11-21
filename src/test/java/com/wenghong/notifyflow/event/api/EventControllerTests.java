package com.wenghong.notifyflow.event.api;

import java.time.Instant;
import java.util.UUID;

import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.wenghong.notifyflow.event.application.EventIngestionService;
import com.wenghong.notifyflow.event.application.IdempotencyConflictException;
import com.wenghong.notifyflow.event.application.EventSnapshot;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventController.class)
@Import(SecurityConfiguration.class)
@WithMockUser(roles = {})
class EventControllerTests {

    @Test
    @WithAnonymousUser
    void anonymousIngestionIsRejectedBeforeCallingService() throws Exception {
        mockMvc.perform(eventRequest().with(anonymous()).with(csrf())
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"order.confirmed\",\"payload\":{}}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void ingestionWithoutCsrfIsRejectedBeforeCallingService() throws Exception {
        mockMvc.perform(eventRequest()
                        .header("Idempotency-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"order.confirmed\",\"payload\":{}}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void ingestionWithInvalidCsrfIsRejectedBeforeCallingService() throws Exception {
        mockMvc.perform(eventRequest().with(csrf().useInvalidToken())
                        .header("Idempotency-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"order.confirmed\",\"payload\":{}}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final TenantAccess ACCESS = new TenantAccess(TENANT_ID, TenantRole.ADMIN);
    @MockitoBean private TenantAccessService tenantAccess;

    @BeforeEach
    void verifiedMembership() {
        when(tenantAccess.requireAccess(TENANT_ID, USER_ID)).thenReturn(ACCESS);
    }

    private MockHttpServletRequestBuilder eventRequest() {
        AppUser account = new AppUser("admin@example.com", "unused-test-hash", Instant.parse("2026-11-20T00:00:00Z"));
        ReflectionTestUtils.setField(account, "id", USER_ID);
        return post("/api/events").with(user(new AppUserDetails(account))).header("X-Tenant-ID", TENANT_ID);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventIngestionService service;

    @Test
    void ingestAcceptsMaximumTypeAndIdempotencyKeyLengths() throws Exception {
        String type = "t".repeat(100);
        String key = "k".repeat(200);
        JsonNode payload = JsonMapper.builder().build().readTree("{}");
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        Instant createdAt = Instant.parse("2026-10-30T00:00:00Z");
        EventSnapshot accepted = new EventSnapshot(id, type, payload, createdAt);
        when(service.ingest(ACCESS, type, payload, key)).thenReturn(accepted);

        mockMvc.perform(eventRequest().with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"%s\",\"payload\":{}}".formatted(type)))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.type").value(type))
                .andExpect(jsonPath("$.createdAt").value(createdAt.toString()));

        verify(service).ingest(ACCESS, type, payload, key);
    }

    @Test
    void ingestRejectsEventTypeLongerThan100Characters() throws Exception {
        mockMvc.perform(eventRequest().with(csrf())
                        .header("Idempotency-Key", "order-128-confirmed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"%s\",\"payload\":{}}".formatted("t".repeat(101))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "", "null"})
    void ingestRejectsMalformedOrMissingBody(String requestBody) throws Exception {
        mockMvc.perform(eventRequest().with(csrf())
                        .header("Idempotency-Key", "order-128-confirmed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void ingestRejectsUnsupportedContentType() throws Exception {
        mockMvc.perform(eventRequest().with(csrf())
                        .header("Idempotency-Key", "order-128-confirmed")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("order.confirmed"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(service);
    }

    @Test
    void ingestReturnsConflictForReusedKeyWithDifferentData() throws Exception {
        String key = "order-128-confirmed";
        JsonNode payload = JsonMapper.builder().build().readTree("{\"orderNumber\":\"999\"}");
        when(service.ingest(ACCESS, "order.confirmed", payload, key))
                .thenThrow(new IdempotencyConflictException());

        mockMvc.perform(eventRequest().with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "order.confirmed",
                                  "payload": {"orderNumber":"999"}
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail")
                        .value("The idempotency key has already been used for a different event."));

        verify(service).ingest(ACCESS, "order.confirmed", payload, key);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"payload\":{}}",
            "{\"type\":\"\",\"payload\":{}}",
            "{\"type\":\"   \",\"payload\":{}}",
            "{\"type\":\"order.confirmed\"}"
    })
    void ingestRejectsMissingOrBlankRequiredBodyFields(String requestBody) throws Exception {
        mockMvc.perform(eventRequest().with(csrf())
                        .header("Idempotency-Key", "order-128-confirmed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void ingestRejectsBlankIdempotencyKey(String key) throws Exception {
        mockMvc.perform(eventRequest().with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "order.confirmed",
                                  "payload": {"orderNumber":"128"}
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void ingestRejectsIdempotencyKeyLongerThan200Characters() throws Exception {
        mockMvc.perform(eventRequest().with(csrf())
                        .header("Idempotency-Key", "x".repeat(201))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "order.confirmed",
                                  "payload": {"orderNumber":"128"}
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void ingestRejectsMissingIdempotencyKey() throws Exception {
        mockMvc.perform(eventRequest().with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "order.confirmed",
                                  "payload": {"orderNumber":"128"}
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void ingestReturnsAcceptedEventResponse() throws Exception {
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        Instant createdAt = Instant.parse("2026-10-30T00:00:00Z");
        String key = "order-128-confirmed";
        JsonNode payload = JsonMapper.builder().build().readTree("""
                {"orderNumber":"128","items":[{"sku":"A","quantity":2}]}
                """);
        EventSnapshot accepted = new EventSnapshot(id, "order.confirmed", payload, createdAt);
        when(service.ingest(ACCESS, "order.confirmed", payload, key)).thenReturn(accepted);

        mockMvc.perform(eventRequest().with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "order.confirmed",
                                  "payload": {
                                    "orderNumber": "128",
                                    "items": [{"sku":"A","quantity":2}]
                                  }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.type").value("order.confirmed"))
                .andExpect(jsonPath("$.createdAt").value(createdAt.toString()))
                .andExpect(jsonPath("$.payload").doesNotExist());

        verify(service).ingest(ACCESS, "order.confirmed", payload, key);
    }
}
