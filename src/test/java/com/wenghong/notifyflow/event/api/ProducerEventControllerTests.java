package com.wenghong.notifyflow.event.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.wenghong.notifyflow.identity.api.ApiKeyController;
import com.wenghong.notifyflow.identity.application.ApiKeyService;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.configuration.ProducerSecurityConfiguration;
import com.wenghong.notifyflow.identity.configuration.SecurityConfiguration;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.security.ApiKeyAuthenticationService;
import com.wenghong.notifyflow.identity.security.ApiKeyPrincipal;
import com.wenghong.notifyflow.identity.security.AppUserDetails;
import com.wenghong.notifyflow.event.application.EventIngestionService;
import com.wenghong.notifyflow.event.application.EventSnapshot;
import com.wenghong.notifyflow.event.application.IdempotencyConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({ProducerEventController.class, EventController.class, ApiKeyController.class})
@Import({SecurityConfiguration.class, ProducerSecurityConfiguration.class})
class ProducerEventControllerTests {
    private static final String ROUTE = "/api/producer/events";
    private static final String RAW = "nf_" + "A".repeat(43);
    private static final ApiKeyPrincipal PRINCIPAL = new ApiKeyPrincipal(UUID.randomUUID(), UUID.randomUUID());
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    private static final String BODY = "{\"type\":\"order.confirmed\",\"payload\":{\"orderId\":128}}";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private MockMvc mvc;
    @MockitoBean private ApiKeyAuthenticationService authentication;
    @MockitoBean private EventIngestionService ingestion;
    @MockitoBean private ApiKeyService management;
    @MockitoBean private TenantAccessService tenantAccess;

    @Test
    void validKeyPublishesWithoutCsrfOrTenantHeaderAndCreatesNoSession() throws Exception {
        acceptKeyAndEvent("order.confirmed", "request-128");
        mvc.perform(request().header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("id").value(EVENT_ID.toString()))
                .andExpect(jsonPath("type").value("order.confirmed"))
                .andExpect(jsonPath("createdAt").value(NOW.toString()))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andExpect(result -> assertNull(result.getRequest().getSession(false)));
        verify(ingestion).ingestForProducer(PRINCIPAL, "order.confirmed", JSON.readTree("{\"orderId\":128}"), "request-128");
        verifyNoInteractions(tenantAccess, management);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void suppliedTenantHeaderCannotOverrideKeyIdentity() throws Exception {
        acceptKeyAndEvent("order.confirmed", "request-128");
        mvc.perform(request().header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW)
                        .header("X-Tenant-ID", UUID.randomUUID()).header("X-User-ID", UUID.randomUUID()))
                .andExpect(status().isAccepted());
        verify(ingestion).ingestForProducer(PRINCIPAL, "order.confirmed", JSON.readTree("{\"orderId\":128}"), "request-128");
        verifyNoInteractions(tenantAccess);
    }

    @Test
    void maximumEventTypeAndIdempotencyLengthsAreAccepted() throws Exception {
        String type = "t".repeat(100);
        String key = "k".repeat(200);
        acceptKeyAndEvent(type, key);
        mvc.perform(post(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"%s\",\"payload\":{\"orderId\":128}}".formatted(type)))
                .andExpect(status().isAccepted());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Basic credentials", "Bearer", "Token value"})
    void missingOrUnsupportedAuthorizationCannotReachIngestion(String authorization) throws Exception {
        var request = request();
        if (!authorization.isEmpty()) request.header(HttpHeaders.AUTHORIZATION, authorization);
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(cookie().doesNotExist("JSESSIONID"));
        verifyNoInteractions(authentication, ingestion, tenantAccess, management);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nf_short", "nf_UnknownKey", ""})
    void rejectedKeyReturns401WithoutCallingIngestion(String raw) throws Exception {
        when(authentication.authenticate(raw)).thenReturn(Optional.empty());
        mvc.perform(request().header(HttpHeaders.AUTHORIZATION, "Bearer " + raw))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(ingestion, tenantAccess, management);
    }

    @Test
    void authenticatedUserSessionCannotSubstituteForProducerKey() throws Exception {
        MockHttpSession session = userSession();
        mvc.perform(request().session(session)).andExpect(status().isUnauthorized());
        verifyNoInteractions(authentication, ingestion, tenantAccess, management);
        assertFalse(session.isInvalid());
    }

    @Test
    void validKeyTakesPrecedenceOverUserSessionWithoutChangingThatSession() throws Exception {
        acceptKeyAndEvent("order.confirmed", "request-128");
        MockHttpSession session = userSession();
        Object original = session.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
        mvc.perform(request().session(session).header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW))
                .andExpect(status().isAccepted()).andExpect(cookie().doesNotExist("JSESSIONID"));
        assertSame(original, session.getAttribute(SPRING_SECURITY_CONTEXT_KEY));
        assertInstanceOf(AppUserDetails.class,
                ((org.springframework.security.core.context.SecurityContext) original).getAuthentication().getPrincipal());
        verify(ingestion).ingestForProducer(PRINCIPAL, "order.confirmed", JSON.readTree("{\"orderId\":128}"), "request-128");
    }

    @Test
    void failedAuthenticationDoesNotLeaveAnAuthenticatedNextRequest() throws Exception {
        acceptKeyAndEvent("order.confirmed", "request-128");
        mvc.perform(request().header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW)).andExpect(status().isAccepted());
        mvc.perform(request()).andExpect(status().isUnauthorized());
        verify(ingestion).ingestForProducer(PRINCIPAL, "order.confirmed", JSON.readTree("{\"orderId\":128}"), "request-128");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/key", "/api/events"})
    void producerKeyCannotAuthenticateManagementOrSessionEventRoutes(String path) throws Exception {
        mvc.perform(post(path).with(csrf()).header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW)
                        .header("X-Tenant-ID", PRINCIPAL.tenantId()).header("Idempotency-Key", "request-128")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(authentication, ingestion, tenantAccess, management);
    }

    @Test
    void sessionManagementRoutesStillRequireCsrf() throws Exception {
        mvc.perform(post("/api/key").session(userSession()).header("X-Tenant-ID", PRINCIPAL.tenantId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Producer\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(authentication, tenantAccess, management);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/producer/admin", "/api/producer/events/extra"})
    void producerPermissionDoesNotAuthorizeOtherProducerPaths(String path) throws Exception {
        acceptKey();
        mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ingestion);
    }

    @Test
    void producerPermissionDoesNotAllowGet() throws Exception {
        acceptKey();
        mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ingestion);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidRequestBodyIsRejectedAfterAuthenticationWithoutIngestion(String body) throws Exception {
        acceptKey();
        mvc.perform(request().header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ingestion);
    }

    @ParameterizedTest
    @MethodSource("invalidIdempotencyKeys")
    void missingBlankOrOversizedIdempotencyKeyIsRejected(String key) throws Exception {
        acceptKey();
        var request = post(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW)
                .contentType(MediaType.APPLICATION_JSON).content(BODY);
        if (key != null) request.header("Idempotency-Key", key);
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(ingestion);
    }

    @Test
    void unsupportedContentTypeIsRejected() throws Exception {
        acceptKey();
        mvc.perform(request().header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW)
                        .contentType(MediaType.TEXT_PLAIN).content("event"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(ingestion);
    }

    @Test
    void idempotencyConflictReturns409ProblemDetail() throws Exception {
        acceptKey();
        when(ingestion.ingestForProducer(PRINCIPAL, "order.confirmed", JSON.readTree("{\"orderId\":128}"), "request-128"))
                .thenThrow(new IdempotencyConflictException());
        mvc.perform(request().header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("status").value(409));
    }

    private void acceptKey() {
        when(authentication.authenticate(RAW)).thenReturn(Optional.of(PRINCIPAL));
    }

    private void acceptKeyAndEvent(String type, String key) {
        acceptKey();
        when(ingestion.ingestForProducer(PRINCIPAL, type, JSON.readTree("{\"orderId\":128}"), key))
                .thenReturn(new EventSnapshot(EVENT_ID, type, JSON.readTree("{\"orderId\":128}"), NOW));
    }

    private MockHttpServletRequestBuilder request() {
        return post(ROUTE).header("Idempotency-Key", "request-128").contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    private MockHttpSession userSession() {
        AppUser user = new AppUser("member@example.com", "unused-test-hash", NOW);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new AppUserDetails(user), null, List.of()));
        var session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    static Stream<String> invalidBodies() {
        return Stream.of("{", "", "null", "{}", "{\"payload\":{}}", "{\"type\":\"\",\"payload\":{}}",
                "{\"type\":\"   \",\"payload\":{}}", "{\"type\":\"order.confirmed\"}",
                "{\"type\":\"%s\",\"payload\":{}}".formatted("t".repeat(101)));
    }

    static Stream<String> invalidIdempotencyKeys() {
        return Stream.of(null, "", "   ", "k".repeat(201));
    }
}
