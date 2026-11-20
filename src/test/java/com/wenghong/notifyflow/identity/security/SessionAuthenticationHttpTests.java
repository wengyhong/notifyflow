package com.wenghong.notifyflow.identity.security;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.api.WebhookEndpointController;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointQueryService;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointService;
import com.wenghong.notifyflow.identity.api.CsrfController;
import com.wenghong.notifyflow.identity.configuration.PasswordConfiguration;
import com.wenghong.notifyflow.identity.configuration.SecurityConfiguration;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.persistence.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({WebhookEndpointController.class, CsrfController.class})
@Import({SecurityConfiguration.class, DatabaseUserDetailsService.class, PasswordConfiguration.class})
class SessionAuthenticationHttpTests {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ENDPOINT_ID = UUID.randomUUID();
    private static final String PASSWORD = "NotifyFlow-test-password-128";
    private static final String LOGIN = "/api/auth/login";
    private static final String LOGOUT = "/api/auth/logout";

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private PasswordEncoder encoder;
    @MockitoBean private AppUserRepository users;
    @MockitoBean private WebhookEndpointService endpoints;
    @MockitoBean private WebhookEndpointQueryService queries;
    private AppUser user;

    @BeforeEach
    void provideStoredUser() {
        user = new AppUser("Alice@example.com", encoder.encode(PASSWORD), Instant.parse("2026-11-20T00:00:00Z"));
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
    }

    @Test
    void anonymousClientCanFetchCsrfTokenWithoutAuthenticating() throws Exception {
        Token token = fetchToken(null);

        assertEquals("X-CSRF-TOKEN", token.headerName());
        assertEquals("_csrf", token.parameterName());
        assertFalse(token.value().isBlank());
        assertNull(token.session().getAttribute(SPRING_SECURITY_CONTEXT_KEY));
        verifyNoInteractions(users, endpoints, queries);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html"})
    void anonymousProtectedRequestReturns401WithoutRedirectOrSavedSession(String accept) throws Exception {
        var result = mvc.perform(get("/api/endpoints/{id}", ENDPOINT_ID).accept(MediaType.parseMediaType(accept)))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Location"))
                .andReturn();

        assertNull(result.getRequest().getSession(false));
        verifyNoInteractions(users, endpoints, queries);
    }

    @Test
    void validHttpBasicCredentialsDoNotAuthenticate() throws Exception {
        mvc.perform(get("/api/endpoints/{id}", ENDPOINT_ID)
                        .with(httpBasic("alice@example.com", PASSWORD)).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"));

        verifyNoInteractions(users, endpoints, queries);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice@example.com", "ALICE@EXAMPLE.COM"})
    void formLoginUsesEmailAndReturns204WithCustomPrincipalInSession(String email) throws Exception {
        when(users.findByEmail(email)).thenReturn(Optional.of(user));
        Token token = fetchToken(null);
        String originalSessionId = token.session().getId();

        MvcResult login = mvc.perform(loginRequest(token, email, PASSWORD))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().doesNotExist("Location")).andReturn();

        MockHttpSession session = session(login);
        assertNotEquals(originalSessionId, session.getId());
        SecurityContext context = context(session);
        assertTrue(context.getAuthentication().isAuthenticated());
        AppUserDetails principal = assertInstanceOf(AppUserDetails.class, context.getAuthentication().getPrincipal());
        assertEquals(USER_ID, principal.getUserId());
        assertEquals("Alice@example.com", principal.getUsername());
        assertTrue(principal.getAuthorities().isEmpty());
        assertNull(principal.getPassword());
        assertNull(context.getAuthentication().getCredentials());

        mvc.perform(get("/api/endpoints/{id}", ENDPOINT_ID).session(session))
                .andExpect(status().isNotFound());

        verify(users).findByEmail(email);
        verify(queries).findById(ENDPOINT_ID);
        verifyNoInteractions(endpoints);
    }

    @ParameterizedTest
    @CsvSource({"alice@example.com,wrong-password", "missing@example.com,NotifyFlow-test-password-128"})
    void invalidCredentialsReturn401WithoutRedirectOrAuthenticatedSession(String email, String password) throws Exception {
        Token token = fetchToken(null);

        var result = mvc.perform(loginRequest(token, email, password))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""))
                .andExpect(header().doesNotExist("Location")).andReturn();

        assertNull(session(result).getAttribute(SPRING_SECURITY_CONTEXT_KEY));
        verifyNoInteractions(endpoints, queries);
    }

    @Test
    void disabledAccountCannotLogInEvenWithCorrectPassword() throws Exception {
        ReflectionTestUtils.setField(user, "enabled", false);
        Token token = fetchToken(null);

        mvc.perform(loginRequest(token, "alice@example.com", PASSWORD))
                .andExpect(status().isUnauthorized());

        assertNull(token.session().getAttribute(SPRING_SECURITY_CONTEXT_KEY));
        verifyNoInteractions(endpoints, queries);
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "password", "both"})
    void missingCredentialsCannotCreateAuthenticatedSession(String missing) throws Exception {
        Token token = fetchToken(null);
        var request = post(LOGIN).session(token.session()).header(token.headerName(), token.value())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (!missing.equals("email") && !missing.equals("both")) {
            request.param("email", "alice@example.com");
        }
        if (!missing.equals("password") && !missing.equals("both")) {
            request.param("password", PASSWORD);
        }

        mvc.perform(request).andExpect(status().isUnauthorized());

        assertNull(token.session().getAttribute(SPRING_SECURITY_CONTEXT_KEY));
        verifyNoInteractions(endpoints, queries);
    }

    @Test
    void usernameParameterCannotReplaceConfiguredEmailParameter() throws Exception {
        Token token = fetchToken(null);

        mvc.perform(post(LOGIN).session(token.session()).header(token.headerName(), token.value())
                        .param("username", "alice@example.com").param("password", PASSWORD))
                .andExpect(status().isUnauthorized());

        verify(users, never()).findByEmail("alice@example.com");
        assertNull(token.session().getAttribute(SPRING_SECURITY_CONTEXT_KEY));
    }

    @Test
    void jsonBodyIsNotTreatedAsFormLoginCredentials() throws Exception {
        Token token = fetchToken(null);

        mvc.perform(post(LOGIN).session(token.session()).header(token.headerName(), token.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());

        verify(users, never()).findByEmail("alice@example.com");
        assertNull(token.session().getAttribute(SPRING_SECURITY_CONTEXT_KEY));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loginWithoutValidCsrfCannotReachUserLookup(boolean invalidToken) throws Exception {
        var request = post(LOGIN).param("email", "alice@example.com").param("password", PASSWORD);
        if (invalidToken) {
            Token token = fetchToken(null);
            request.session(token.session()).header(token.headerName(), "invalid-token");
        }

        mvc.perform(request).andExpect(status().isForbidden());

        verifyNoInteractions(users, endpoints, queries);
    }

    @Test
    void successfulLoginRequiresFreshCsrfTokenForSubsequentWrites() throws Exception {
        Token beforeLogin = fetchToken(null);
        MockHttpSession session = login(beforeLogin);

        mvc.perform(patch("/api/endpoints/{id}/enabled", ENDPOINT_ID).session(session)
                        .header(beforeLogin.headerName(), beforeLogin.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(endpoints);

        Token afterLogin = fetchToken(session);
        mvc.perform(patch("/api/endpoints/{id}/enabled", ENDPOINT_ID).session(session)
                        .header(afterLogin.headerName(), afterLogin.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());

        verify(endpoints).changeEnabled(ENDPOINT_ID, false);
    }

    @Test
    void logoutInvalidatesSessionAndDeletesSessionCookie() throws Exception {
        MockHttpSession session = login(fetchToken(null));
        Token token = fetchToken(session);

        mvc.perform(post(LOGOUT).session(session).header(token.headerName(), token.value()))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(cookie().maxAge("JSESSIONID", 0));

        assertTrue(session.isInvalid());
        mvc.perform(get("/api/endpoints/{id}", ENDPOINT_ID).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        Token afterLogout = fetchToken(null);
        assertNull(afterLogout.session().getAttribute(SPRING_SECURITY_CONTEXT_KEY));
        verifyNoInteractions(endpoints, queries);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void logoutWithoutValidCsrfPreservesAuthenticatedSession(boolean invalidToken) throws Exception {
        MockHttpSession session = login(fetchToken(null));
        Token token = fetchToken(session);
        var request = post(LOGOUT).session(session);
        if (invalidToken) {
            request.header(token.headerName(), "invalid-token");
        }

        mvc.perform(request).andExpect(status().isForbidden());

        assertFalse(session.isInvalid());
        assertTrue(context(session).getAuthentication().isAuthenticated());
        mvc.perform(get("/api/endpoints/{id}", ENDPOINT_ID).session(session))
                .andExpect(status().isNotFound());
        verify(queries).findById(ENDPOINT_ID);
    }

    @Test
    void csrfPermissionDoesNotExposeOtherMethodsOrSubpaths() throws Exception {
        Token token = fetchToken(null);

        mvc.perform(post("/api/auth/csrf").session(token.session())
                        .header(token.headerName(), token.value())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/csrf/other")).andExpect(status().isUnauthorized());

        verifyNoInteractions(users, endpoints, queries);
    }

    private Token fetchToken(MockHttpSession existingSession) throws Exception {
        var request = get("/api/auth/csrf");
        if (existingSession != null) {
            request.session(existingSession);
        }
        var result = mvc.perform(request).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.headerName").isString())
                .andExpect(jsonPath("$.parameterName").isString())
                .andExpect(jsonPath("$.token").isNotEmpty()).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        return new Token(session(result), body.get("headerName").stringValue(),
                body.get("parameterName").stringValue(), body.get("token").stringValue());
    }

    private MockHttpServletRequestBuilder loginRequest(Token token, String email, String password) {
        String originalId = token.session().getId();
        return post(LOGIN).session(token.session()).header(token.headerName(), token.value())
                .with(request -> {
                    request.setRequestedSessionId(originalId);
                    request.setRequestedSessionIdValid(true);
                    return request;
                })
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", email).param("password", password);
    }

    private MockHttpSession login(Token token) throws Exception {
        return session(mvc.perform(loginRequest(token, "alice@example.com", PASSWORD))
                .andExpect(status().isNoContent()).andReturn());
    }

    private MockHttpSession session(MvcResult result) {
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private SecurityContext context(MockHttpSession session) {
        return assertInstanceOf(SecurityContext.class, session.getAttribute(SPRING_SECURITY_CONTEXT_KEY));
    }

    private record Token(MockHttpSession session, String headerName, String parameterName, String value) {
    }
}
