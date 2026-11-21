package com.wenghong.notifyflow.endpoint.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.application.SecretRotationInProgressException;
import com.wenghong.notifyflow.endpoint.application.WebhookSecretRotationService;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
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

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookSecretRotationController.class)
@Import(SecurityConfiguration.class)
@WithMockUser(roles = {})
class WebhookSecretRotationControllerTests {

    @Test
    @WithAnonymousUser
    void anonymousRotationIsRejectedBeforeCallingService() throws Exception {
        mvc.perform(rotationRequest(ID).with(anonymous()).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void rotationWithoutCsrfIsRejectedBeforeCallingService() throws Exception {
        mvc.perform(rotationRequest(ID)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void rotationWithInvalidCsrfIsRejectedBeforeCallingService() throws Exception {
        mvc.perform(rotationRequest(ID).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private static final UUID ID = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
    private static final String NEW_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String OLD_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final Instant EXPIRY = Instant.parse("2026-11-17T01:00:00Z");
    private static final String ROUTE = "/api/endpoints/{id}/signing-secret/rotate";

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final TenantAccess ACCESS = new TenantAccess(TENANT_ID, TenantRole.ADMIN);

    @MockitoBean private TenantAccessService tenantAccess;

    @BeforeEach
    void verifiedMembership() {
        when(tenantAccess.requireAccess(TENANT_ID, USER_ID)).thenReturn(ACCESS);
    }

    private MockHttpServletRequestBuilder rotationRequest(Object id) {
        AppUser account = new AppUser("admin@example.com", "unused-test-hash", Instant.parse("2026-11-20T00:00:00Z"));
        ReflectionTestUtils.setField(account, "id", USER_ID);
        return post(ROUTE, id).with(user(new AppUserDetails(account))).header("X-Tenant-ID", TENANT_ID);
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private WebhookSecretRotationService service;

    @Test
    void rotationWithoutRequestBodyReturnsOnlyNewSecretAndExpiry() throws Exception {
        WebhookEndpoint endpoint = mock(WebhookEndpoint.class);
        when(endpoint.getId()).thenReturn(ID);
        when(endpoint.getSigningSecret()).thenReturn(NEW_SECRET);
        when(endpoint.getPreviousSigningSecret()).thenReturn(OLD_SECRET);
        when(endpoint.getPreviousSigningSecretExpiresAt()).thenReturn(EXPIRY);
        when(service.rotate(ACCESS, ID)).thenReturn(Optional.of(endpoint));

        mvc.perform(rotationRequest(ID).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.signingSecret").value(NEW_SECRET))
                .andExpect(jsonPath("$.previousSigningSecretExpiresAt").value(EXPIRY.toString()))
                .andExpect(jsonPath("$.previousSigningSecret").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.*", hasSize(3)))
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains(OLD_SECRET)));

        verify(service).rotate(ACCESS, ID);
    }

    @Test
    void missingEndpointReturnsEmptyNotFoundResponse() throws Exception {
        when(service.rotate(ACCESS, ID)).thenReturn(Optional.empty());

        mvc.perform(rotationRequest(ID).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        verify(service).rotate(ACCESS, ID);
    }

    @Test
    void activeOverlapReturnsConflictProblemDetail() throws Exception {
        when(service.rotate(ACCESS, ID)).thenThrow(new SecretRotationInProgressException());

        mvc.perform(rotationRequest(ID).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("A signing secret rotation is already in progress"))
                .andExpect(jsonPath("$.instance").value(
                        "/api/endpoints/" + ID + "/signing-secret/rotate"))
                .andExpect(jsonPath("$.signingSecret").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.previousSigningSecret").doesNotHaveJsonPath());

        verify(service).rotate(ACCESS, ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "123", "a8a1d32a-a014-47bb-bf82-f31ea55e4acg"})
    void malformedIdReturnsBadRequestWithoutCallingService(String id) throws Exception {
        mvc.perform(rotationRequest(id).with(csrf())).andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void getCannotTriggerRotation() throws Exception {
        mvc.perform(get(ROUTE, ID)).andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(service);
    }

    @Test
    void responseTextRepresentationExcludesSecret() {
        var response = new WebhookSecretRotatedResponse(ID, NEW_SECRET, EXPIRY);

        assertFalse(response.toString().contains(NEW_SECRET));
        assertTrue(response.toString().contains(ID.toString()));
        assertTrue(response.toString().contains(EXPIRY.toString()));
    }
}
