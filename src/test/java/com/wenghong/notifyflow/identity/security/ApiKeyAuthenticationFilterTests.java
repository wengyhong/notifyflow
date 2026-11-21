package com.wenghong.notifyflow.identity.security;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiKeyAuthenticationFilterTests {
    private static final String RAW = "nf_" + "A".repeat(43);
    private static final ApiKeyPrincipal PRINCIPAL = new ApiKeyPrincipal(UUID.randomUUID(), UUID.randomUUID());
    private final ApiKeyAuthenticationService service = mock(ApiKeyAuthenticationService.class);
    private final ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(service);

    @AfterEach
    void clearThreadContext() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer", "bearer", "BEARER"})
    void validKeyProvidesOnlyPublishAuthorityAndContextIsClearedAfterRequest(String scheme) throws Exception {
        when(service.authenticate(RAW)).thenReturn(Optional.of(PRINCIPAL));
        var request = request(scheme + " " + RAW);
        var response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> {
            called.set(true);
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertTrue(authentication.isAuthenticated());
            assertSame(PRINCIPAL, authentication.getPrincipal());
            assertNull(authentication.getCredentials());
            assertEquals(java.util.List.of("EVENT_PUBLISH"), authentication.getAuthorities().stream()
                    .map(authority -> authority.getAuthority()).toList());
        });
        assertTrue(called.get());
        assertEquals(200, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(service).authenticate(RAW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Basic credentials", "Bearer", "Token ignored"})
    void missingOrWrongSchemeStopsBeforeAuthenticationOrController(String header) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(header.isEmpty() ? null : header), response,
                (req, res) -> fail("Controller must not run"));
        assertEquals(401, response.getStatus());
        verifyNoInteractions(service);
    }

    @Test
    void rejectedKeyDoesNotRunController() throws Exception {
        when(service.authenticate(RAW)).thenReturn(Optional.empty());
        var response = new MockHttpServletResponse();
        filter.doFilter(request("Bearer " + RAW), response, (req, res) -> fail("Controller must not run"));
        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void downstreamFailureStillClearsAuthentication() {
        when(service.authenticate(RAW)).thenReturn(Optional.of(PRINCIPAL));
        var failure = new ServletException("Test downstream failure");
        assertSame(failure, assertThrows(ServletException.class, () -> filter.doFilter(
                request("Bearer " + RAW), new MockHttpServletResponse(), (req, res) -> { throw failure; })));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void databaseOutageIsNotConvertedIntoBadCredentials() {
        var failure = new DataAccessResourceFailureException("Test database outage");
        when(service.authenticate(RAW)).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> filter.doFilter(
                request("Bearer " + RAW), new MockHttpServletResponse(), (req, res) -> fail("Controller must not run"))));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private MockHttpServletRequest request(String header) {
        var request = new MockHttpServletRequest("POST", "/api/producer/events");
        if (header != null) request.addHeader(HttpHeaders.AUTHORIZATION, header);
        return request;
    }
}
