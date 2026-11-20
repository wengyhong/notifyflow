package com.wenghong.notifyflow.identity.api;

import java.util.UUID;
import java.util.stream.Stream;

import com.wenghong.notifyflow.identity.application.AccountRegistrationResult;
import com.wenghong.notifyflow.identity.application.AccountRegistrationService;
import com.wenghong.notifyflow.identity.application.EmailAlreadyRegisteredException;
import com.wenghong.notifyflow.identity.application.InvalidRegistrationPasswordException;
import com.wenghong.notifyflow.identity.configuration.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AccountRegistrationController.class, CsrfController.class})
@Import(SecurityConfiguration.class)
class AccountRegistrationControllerTests {

    private static final String ROUTE = "/api/auth/register";
    private static final String EMAIL = "Alice@example.com";
    private static final String PASSWORD = "NotifyFlow-test-password-128";
    private static final String TENANT = "Company A";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @MockitoBean private AccountRegistrationService service;

    @Test
    void anonymousRegistrationWithRealCsrfReturnsOnlyIdsAndDoesNotAuthenticate() throws Exception {
        var csrfResponse = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) csrfResponse.getRequest().getSession(false);
        assertNotNull(session);
        var token = json.readTree(csrfResponse.getResponse().getContentAsString());
        when(service.register(EMAIL, PASSWORD, TENANT)).thenReturn(new AccountRegistrationResult(USER_ID, TENANT_ID));

        mvc.perform(post(ROUTE).session(session)
                        .header(token.get("headerName").stringValue(), token.get("token").stringValue())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body())))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.*", hasSize(2)))
                .andExpect(jsonPath("$.password").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.passwordHash").doesNotHaveJsonPath())
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains(PASSWORD)));

        verify(service).register(EMAIL, PASSWORD, TENANT);
        assertNull(session.getAttribute(SPRING_SECURITY_CONTEXT_KEY));
        mvc.perform(get("/api/endpoints").session(session)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @MethodSource("validPasswords")
    void acceptsPasswordPolicyBoundaries(String password) throws Exception {
        when(service.register(EMAIL, password, TENANT)).thenReturn(new AccountRegistrationResult(USER_ID, TENANT_ID));

        mvc.perform(post(ROUTE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body().put("password", password))))
                .andExpect(status().isCreated());

        verify(service).register(EMAIL, password, TENANT);
    }

    static Stream<String> validPasswords() {
        return Stream.of("a".repeat(15), "a".repeat(72), "😀".repeat(15), "界".repeat(24));
    }

    @ParameterizedTest(name = "invalid {0}: {2}")
    @MethodSource("invalidFields")
    void invalidRequestDoesNotReachService(String field, String value, String description) throws Exception {
        ObjectNode body = body();
        if (value == null) {
            body.putNull(field);
        } else {
            body.put(field, value);
        }

        mvc.perform(post(ROUTE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("email", null, "null"), Arguments.of("email", "", "empty"),
                Arguments.of("email", " ", "blank"), Arguments.of("email", "not-an-email", "invalid syntax"),
                Arguments.of("email", "a".repeat(255), "beyond database length"),
                Arguments.of("password", null, "null"), Arguments.of("password", "", "empty"),
                Arguments.of("password", " ".repeat(15), "all spaces"),
                Arguments.of("password", "a".repeat(14), "under minimum"),
                Arguments.of("password", "a".repeat(73), "over byte maximum"),
                Arguments.of("password", "😀".repeat(14), "UTF-16 length is not character count"),
                Arguments.of("password", "😀".repeat(19), "multibyte password exceeds maximum"),
                Arguments.of("tenantName", null, "null"), Arguments.of("tenantName", "", "empty"),
                Arguments.of("tenantName", " ", "blank"),
                Arguments.of("tenantName", "x".repeat(101), "over maximum"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "password", "tenantName"})
    void missingRequiredFieldDoesNotReachService(String field) throws Exception {
        ObjectNode body = body();
        body.remove(field);

        mvc.perform(post(ROUTE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "", "null", "[]"})
    void malformedOrMissingBodyDoesNotReachService(String body) throws Exception {
        mvc.perform(post(ROUTE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void unsupportedContentTypeDoesNotReachService() throws Exception {
        mvc.perform(post(ROUTE).with(csrf()).contentType(MediaType.TEXT_PLAIN).content("registration"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrInvalidCsrfIsRejectedBeforeService(boolean invalidToken) throws Exception {
        var request = post(ROUTE).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body()));
        if (invalidToken) {
            request.with(csrf().useInvalidToken());
        }

        mvc.perform(request).andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void duplicateEmailReturns409WithoutDatabaseDiagnosticOrPassword() throws Exception {
        String diagnostic = "internal-database-diagnostic";
        when(service.register(EMAIL, PASSWORD, TENANT))
                .thenThrow(new EmailAlreadyRegisteredException(new IllegalStateException(diagnostic)));

        mvc.perform(post(ROUTE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body())))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("An account with this email already exists."))
                .andExpect(jsonPath("$.cause").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.stackTrace").doesNotHaveJsonPath())
                .andExpect(result -> {
                    String text = result.getResponse().getContentAsString();
                    assertFalse(text.contains(diagnostic));
                    assertFalse(text.contains(PASSWORD));
                });
    }

    @Test
    void servicePasswordPolicyFailureReturns400() throws Exception {
        when(service.register(EMAIL, PASSWORD, TENANT)).thenThrow(new InvalidRegistrationPasswordException());

        mvc.perform(post(ROUTE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Password does not meet the registration requirements."));
    }

    @Test
    void anonymousPermissionIsLimitedToPostOnExactRegistrationRoute() throws Exception {
        mvc.perform(get(ROUTE)).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
        mvc.perform(post(ROUTE + "/other").with(csrf())).andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    private ObjectNode body() {
        return json.createObjectNode().put("email", EMAIL).put("password", PASSWORD).put("tenantName", TENANT);
    }
}
