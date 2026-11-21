package com.wenghong.notifyflow.endpoint.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.application.WebhookEndpointService;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointQueryService;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointSnapshot;
import com.wenghong.notifyflow.endpoint.application.InvalidWebhookUrlException;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import com.wenghong.notifyflow.identity.configuration.SecurityConfiguration;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookEndpointController.class)
@Import(SecurityConfiguration.class)
@WithMockUser(roles = {})
class WebhookEndpointControllerTests {

    @Test
    @WithAnonymousUser
    void anonymousDetailLookupIsRejectedBeforeCallingQueryService() throws Exception {
        mockMvc.perform(get("/api/endpoints/{id}", UUID.randomUUID()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service, queryService);
    }

    @Test
    @WithAnonymousUser
    void anonymousEndpointCreationIsRejectedBeforeCallingService() throws Exception {
        mockMvc.perform(createRequest().with(anonymous())
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Orders\",\"url\":\"https://example.com/webhook\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service, queryService);
    }

    @Test
    void endpointChangeWithoutCsrfIsRejectedBeforeCallingService() throws Exception {
        mockMvc.perform(patch("/api/endpoints/{id}/enabled", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service, queryService);
    }

    @Test
    void endpointChangeWithInvalidCsrfIsRejectedBeforeCallingService() throws Exception {
        mockMvc.perform(patch("/api/endpoints/{id}/enabled", UUID.randomUUID())
                        .with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service, queryService);
    }

    private static final String SIGNING_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final TenantAccess ACCESS = new TenantAccess(TENANT_ID, TenantRole.ADMIN);

    @MockitoBean
    private TenantAccessService tenantAccessService;

    @BeforeEach
    void configureVerifiedMembership() {
        when(tenantAccessService.requireAccess(TENANT_ID, USER_ID)).thenReturn(ACCESS);
    }

    private MockHttpServletRequestBuilder createRequest() {
        AppUser account = new AppUser("admin@example.com", "unused-test-hash", Instant.parse("2026-11-20T00:00:00Z"));
        ReflectionTestUtils.setField(account, "id", USER_ID);
        return post("/api/endpoints").with(user(new AppUserDetails(account)))
                .with(csrf()).header("X-Tenant-ID", TENANT_ID);
    }

    private MockHttpServletRequestBuilder detailRequest(Object id) {
        AppUser account = new AppUser("admin@example.com", "unused-test-hash", Instant.parse("2026-11-20T00:00:00Z"));
        ReflectionTestUtils.setField(account, "id", USER_ID);
        return get("/api/endpoints/{id}", id).with(user(new AppUserDetails(account)))
                .header("X-Tenant-ID", TENANT_ID);
    }

    private MockHttpServletRequestBuilder listRequest() {
        AppUser account = new AppUser("admin@example.com", "unused-test-hash", Instant.parse("2026-11-20T00:00:00Z"));
        ReflectionTestUtils.setField(account, "id", USER_ID);
        return get("/api/endpoints").with(user(new AppUserDetails(account)))
                .header("X-Tenant-ID", TENANT_ID);
    }

    private MockHttpServletRequestBuilder patchRequest(Object id) {
        AppUser account = new AppUser("admin@example.com", "unused-test-hash", Instant.parse("2026-11-20T00:00:00Z"));
        ReflectionTestUtils.setField(account, "id", USER_ID);
        return patch("/api/endpoints/{id}/enabled", id).with(user(new AppUserDetails(account)))
                .header("X-Tenant-ID", TENANT_ID);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WebhookEndpointService service;

    @MockitoBean
    private WebhookEndpointQueryService queryService;

    @Test
    void policyRejectionReturnsBadRequestProblemDetail() throws Exception {
        String url = "https://example.com/webhooks";
        String detail = "Webhook URL must not contain credentials";
        when(service.create(ACCESS, "Orders", url))
                .thenThrow(new InvalidWebhookUrlException(detail));

        mockMvc.perform(createRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Orders","url":"https://example.com/webhooks"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").value("/api/endpoints"))
                .andExpect(jsonPath("$.signingSecret").doesNotHaveJsonPath());

        verify(service).create(ACCESS, "Orders", url);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void changeEnabledReturnsUpdatedEndpoint(boolean enabled) throws Exception {
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        Instant createdAt = Instant.parse("2026-10-28T00:00:00Z");
        Instant updatedAt = createdAt.plusSeconds(60);
        WebhookEndpoint endpoint = mock(WebhookEndpoint.class);
        when(endpoint.getId()).thenReturn(id);
        when(endpoint.getName()).thenReturn("Orders");
        when(endpoint.getUrl()).thenReturn("https://example.com/webhooks");
        when(endpoint.isEnabled()).thenReturn(enabled);
        when(endpoint.getCreatedAt()).thenReturn(createdAt);
        when(endpoint.getUpdatedAt()).thenReturn(updatedAt);
        when(endpoint.getSigningSecret()).thenReturn(SIGNING_SECRET);
        when(service.changeEnabled(ACCESS, id, enabled)).thenReturn(Optional.of(endpoint));

        mockMvc.perform(patchRequest(id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":%s}".formatted(enabled)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Orders"))
                .andExpect(jsonPath("$.url").value("https://example.com/webhooks"))
                .andExpect(jsonPath("$.enabled").value(enabled))
                .andExpect(jsonPath("$.createdAt").value(createdAt.toString()))
                .andExpect(jsonPath("$.updatedAt").value(updatedAt.toString()))
                .andExpect(jsonPath("$.signingSecret").doesNotHaveJsonPath());

        verify(service).changeEnabled(ACCESS, id, enabled);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void changeEnabledReturnsNotFoundForMissingEndpoint(boolean enabled) throws Exception {
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        when(service.changeEnabled(ACCESS, id, enabled)).thenReturn(Optional.empty());

        mockMvc.perform(patchRequest(id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":%s}".formatted(enabled)))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        verify(service).changeEnabled(ACCESS, id, enabled);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"enabled\":null}", "{", "", "null",
            "{\"enabled\":{}}", "{\"enabled\":\"invalid\"}"})
    void changeEnabledRejectsInvalidBodyWithoutCallingService(String requestBody) throws Exception {
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");

        mockMvc.perform(patchRequest(id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void changeEnabledRejectsMalformedUuid() throws Exception {
        mockMvc.perform(patchRequest("not-a-uuid").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void changeEnabledRejectsUnsupportedContentType() throws Exception {
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");

        mockMvc.perform(patchRequest(id).with(csrf())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("false"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(service);
    }

    @Test
    void findAllUsesDefaultPaginationAndReturnsEmptyPage() throws Exception {
        PageRequest pageable = PageRequest.of(
                0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        when(service.findAll(ACCESS, pageable)).thenReturn(Page.empty(pageable));

        mockMvc.perform(listRequest())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));

        verify(service).findAll(ACCESS, pageable);
    }

    @Test
    void findAllBindsExplicitPaginationAndSortAndMapsEndpointContent() throws Exception {
        PageRequest pageable = PageRequest.of(
                1, 2, Sort.by("name").ascending().and(Sort.by("id").ascending()));
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = mock(WebhookEndpoint.class);
        when(endpoint.getId()).thenReturn(id);
        when(endpoint.getName()).thenReturn("Orders");
        when(endpoint.getUrl()).thenReturn("https://example.com/webhooks");
        when(endpoint.isEnabled()).thenReturn(true);
        when(endpoint.getCreatedAt()).thenReturn(now);
        when(endpoint.getUpdatedAt()).thenReturn(now);
        UUID secondId = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4ace");
        WebhookEndpoint secondEndpoint = mock(WebhookEndpoint.class);
        when(secondEndpoint.getId()).thenReturn(secondId);
        when(secondEndpoint.getName()).thenReturn("Payments");
        when(secondEndpoint.getUrl()).thenReturn("https://example.com/payments");
        when(secondEndpoint.isEnabled()).thenReturn(false);
        when(secondEndpoint.getCreatedAt()).thenReturn(now);
        when(secondEndpoint.getUpdatedAt()).thenReturn(now);
        when(endpoint.getSigningSecret()).thenReturn(SIGNING_SECRET);
        when(secondEndpoint.getSigningSecret()).thenReturn(SIGNING_SECRET);
        when(service.findAll(ACCESS, pageable))
                .thenReturn(new PageImpl<>(List.of(endpoint, secondEndpoint), pageable, 5));

        mockMvc.perform(listRequest()
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "name,asc", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].name").value("Orders"))
                .andExpect(jsonPath("$.content[0].url").value("https://example.com/webhooks"))
                .andExpect(jsonPath("$.content[0].enabled").value(true))
                .andExpect(jsonPath("$.content[0].createdAt").value(now.toString()))
                .andExpect(jsonPath("$.content[0].updatedAt").value(now.toString()))
                .andExpect(jsonPath("$.content[1].id").value(secondId.toString()))
                .andExpect(jsonPath("$.content[1].name").value("Payments"))
                .andExpect(jsonPath("$.content[1].url").value("https://example.com/payments"))
                .andExpect(jsonPath("$.content[1].enabled").value(false))
                .andExpect(jsonPath("$.content[1].createdAt").value(now.toString()))
                .andExpect(jsonPath("$.content[1].updatedAt").value(now.toString()))
                .andExpect(jsonPath("$.content[0].signingSecret").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[1].signingSecret").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));

        verify(service).findAll(ACCESS, pageable);
    }

    @Test
    void findAllReturnsEmptyContentBeyondLastPageWhilePreservingTotals() throws Exception {
        PageRequest pageable = PageRequest.of(
                5, 2, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        when(service.findAll(ACCESS, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 3));

        mockMvc.perform(listRequest().param("page", "5").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(5))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        verify(service).findAll(ACCESS, pageable);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void findByIdReturnsSnapshotResponseFromQueryService(boolean enabled) throws Exception {
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        Instant updated = now.plusSeconds(60);
        WebhookEndpointSnapshot snapshot = new WebhookEndpointSnapshot(
                id, TENANT_ID, "Orders", "https://example.com/webhooks", enabled, now, updated);
        when(queryService.findById(ACCESS, id)).thenReturn(Optional.of(snapshot));

        mockMvc.perform(detailRequest(id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Orders"))
                .andExpect(jsonPath("$.url").value("https://example.com/webhooks"))
                .andExpect(jsonPath("$.enabled").value(enabled))
                .andExpect(jsonPath("$.createdAt").value(now.toString()))
                .andExpect(jsonPath("$.updatedAt").value(updated.toString()))
                .andExpect(jsonPath("$.signingSecret").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.previousSigningSecret").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.previousSigningSecretExpiresAt").doesNotHaveJsonPath());

        verify(queryService).findById(ACCESS, id);
        verifyNoInteractions(service);
    }

    @Test
    void findByIdReturnsNotFoundWhenEndpointDoesNotExist() throws Exception {
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        when(queryService.findById(ACCESS, id)).thenReturn(Optional.empty());

        mockMvc.perform(detailRequest(id))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        verify(queryService).findById(ACCESS, id);
        verifyNoInteractions(service);
    }

    @Test
    void findByIdRejectsMalformedUuid() throws Exception {
        mockMvc.perform(detailRequest("not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service, queryService);
    }

    @Test
    void createReturnsCreatedWithSigningSecret() throws Exception {
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = mock(WebhookEndpoint.class);
        when(endpoint.getId()).thenReturn(id);
        when(endpoint.getName()).thenReturn("Orders");
        when(endpoint.getUrl()).thenReturn("https://example.com/webhooks");
        when(endpoint.isEnabled()).thenReturn(true);
        when(endpoint.getCreatedAt()).thenReturn(now);
        when(endpoint.getUpdatedAt()).thenReturn(now);
        when(service.create(ACCESS, "Orders", "https://example.com/webhooks"))
                .thenReturn(endpoint);
        when(endpoint.getSigningSecret()).thenReturn(SIGNING_SECRET);

        mockMvc.perform(createRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Orders",
                                  "url": "https://example.com/webhooks"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Orders"))
                .andExpect(jsonPath("$.url").value("https://example.com/webhooks"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.createdAt").value(now.toString()))
                .andExpect(jsonPath("$.updatedAt").value(now.toString()))
                .andExpect(jsonPath("$.signingSecret").value(SIGNING_SECRET));

        verify(service).create(ACCESS, "Orders", "https://example.com/webhooks");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-url",
            "/relative/path",
            "ftp://example.com/webhook",
            "https://"
    })
    void createRejectsInvalidWebhookUrl(String url) throws Exception {
        mockMvc.perform(createRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Orders",
                                  "url": "%s"
                                }
                                """.formatted(url)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/webhooks",
            "https://example.com/webhooks"
    })
    void createAcceptsHttpAndHttpsWebhookUrls(String url) throws Exception {
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = mock(WebhookEndpoint.class);
        when(endpoint.getId()).thenReturn(id);
        when(endpoint.getName()).thenReturn("Orders");
        when(endpoint.getUrl()).thenReturn(url);
        when(endpoint.isEnabled()).thenReturn(true);
        when(endpoint.getCreatedAt()).thenReturn(now);
        when(endpoint.getUpdatedAt()).thenReturn(now);
        when(service.create(ACCESS, "Orders", url)).thenReturn(endpoint);

        mockMvc.perform(createRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Orders",
                                  "url": "%s"
                                }
                                """.formatted(url)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.url").value(url));

        verify(service).create(ACCESS, "Orders", url);
    }

    @Test
    void createRejectsBlankName() throws Exception {
        mockMvc.perform(createRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "   ",
                                  "url": "https://example.com/webhooks"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void createRejectsBlankUrl() throws Exception {
        mockMvc.perform(createRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Orders",
                                  "url": "   "
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void createRejectsNameLongerThan100Characters() throws Exception {
        String name = "x".repeat(101);

        mockMvc.perform(createRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "url": "https://example.com/webhooks"
                                }
                                """.formatted(name)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"url\":\"https://example.com/webhooks\"}",
            "{\"name\":null,\"url\":\"https://example.com/webhooks\"}",
            "{\"name\":\"\",\"url\":\"https://example.com/webhooks\"}",
            "{\"name\":\"Orders\"}",
            "{\"name\":\"Orders\",\"url\":null}",
            "{\"name\":\"Orders\",\"url\":\"\"}"
    })
    void createRejectsMissingNullOrEmptyRequiredFields(String requestBody) throws Exception {
        mockMvc.perform(createRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void createAcceptsNameExactly100CharactersLong() throws Exception {
        String name = "x".repeat(100);
        String url = "https://example.com/webhooks";
        UUID id = UUID.fromString("a8a1d32a-a014-47bb-bf82-f31ea55e4acd");
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        WebhookEndpoint endpoint = mock(WebhookEndpoint.class);
        when(endpoint.getId()).thenReturn(id);
        when(endpoint.getName()).thenReturn(name);
        when(endpoint.getUrl()).thenReturn(url);
        when(endpoint.isEnabled()).thenReturn(true);
        when(endpoint.getCreatedAt()).thenReturn(now);
        when(endpoint.getUpdatedAt()).thenReturn(now);
        when(service.create(ACCESS, name, url)).thenReturn(endpoint);

        mockMvc.perform(createRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "url": "https://example.com/webhooks"
                                }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value(name));

        verify(service).create(ACCESS, name, url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "", "null"})
    void createRejectsMalformedOrMissingBody(String requestBody) throws Exception {
        mockMvc.perform(createRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void createRejectsUnsupportedContentType() throws Exception {
        mockMvc.perform(createRequest()
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("Orders"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(service);
    }
}
