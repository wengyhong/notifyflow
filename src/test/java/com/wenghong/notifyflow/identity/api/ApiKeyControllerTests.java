package com.wenghong.notifyflow.identity.api;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.wenghong.notifyflow.identity.application.ApiKeyGenerator;
import com.wenghong.notifyflow.identity.application.ApiKeyHasher;
import com.wenghong.notifyflow.identity.application.ApiKeyService;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.configuration.SecurityConfiguration;
import com.wenghong.notifyflow.identity.domain.ApiKey;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantMembership;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.ApiKeyRepository;
import com.wenghong.notifyflow.identity.persistence.TenantMembershipRepository;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;
import com.wenghong.notifyflow.identity.security.AppUserDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Exercise the real membership and creation services so role guards cannot be hidden by mocks.
@WebMvcTest(ApiKeyController.class)
@Import({SecurityConfiguration.class, TenantAccessService.class, ApiKeyService.class})
class ApiKeyControllerTests {
    private static final String ROUTE = "/api/key";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID KEY_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    private static final String RAW = "nf_" + "A".repeat(43);
    private static final String HASH = "b".repeat(64);
    @Autowired private MockMvc mvc;
    @MockitoBean private TenantMembershipRepository memberships;
    @MockitoBean private TenantRepository tenants;
    @MockitoBean private ApiKeyRepository keys;
    @MockitoBean private ApiKeyGenerator generator;
    @MockitoBean private ApiKeyHasher hasher;
    @MockitoBean private Clock clock;

    @ParameterizedTest
    @MethodSource("validNames")
    void administratorCreatesKeyWith201RawKeyAndNoStoreButNoHash(String name) throws Exception {
        Tenant tenant = configureCreation();

        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID)
                        .content("{\"name\":\"%s\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("id").value(KEY_ID.toString()))
                .andExpect(jsonPath("name").value(name))
                .andExpect(jsonPath("rawKey").value(RAW))
                .andExpect(jsonPath("createdAt").value(NOW.toString()))
                .andExpect(jsonPath("keyHash").doesNotExist())
                .andExpect(jsonPath("$.*", hasSize(4)));

        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        var saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(keys).save(saved.capture());
        assertSame(tenant, saved.getValue().getTenant());
        assertEquals(name, saved.getValue().getName());
        assertEquals(HASH, saved.getValue().getKeyHash());
        verify(hasher).hash(RAW);
    }

    @Test
    void clientCannotChooseTenantOrSecretThroughExtraBodyFields() throws Exception {
        Tenant tenant = configureCreation();
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID).content("""
                        {"name":"Producer","tenantId":"00000000-0000-0000-0000-000000000099",
                         "rawKey":"client-chosen-key","keyHash":"client-chosen-hash"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("rawKey").value(RAW));
        var saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(keys).save(saved.capture());
        assertSame(tenant, saved.getValue().getTenant());
        assertEquals(HASH, saved.getValue().getKeyHash());
    }

    @Test
    void viewerCannotPromoteThemselvesThroughAHeader() throws Exception {
        membership(TenantRole.VIEWER);
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID).header("X-Tenant-Role", "ADMIN"))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoCreationWork();
    }

    @Test
    void nonmemberCannotBorrowAnotherUsersIdentity() throws Exception {
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID).header("X-User-ID", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoCreationWork();
    }

    @Test
    void administratorOfAnotherTenantCannotCreateKeysHere() throws Exception {
        membership(TenantRole.ADMIN);
        UUID otherTenant = UUID.randomUUID();
        when(memberships.findByTenant_IdAndUser_Id(otherTenant, USER_ID)).thenReturn(Optional.empty());
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", otherTenant))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(otherTenant, USER_ID);
        verifyNoCreationWork();
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidNameOrJsonIsRejectedBeforeMembershipLookupOrGeneration(String body) throws Exception {
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(memberships);
        verifyNoCreationWork();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void missingOrMalformedTenantHeaderIsRejectedBeforeLookup(String header) throws Exception {
        var request = request().with(csrf());
        if (!header.isEmpty()) request.header("X-Tenant-ID", header);
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(memberships);
        verifyNoCreationWork();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrInvalidCsrfIsRejectedBeforeLookup(boolean invalidToken) throws Exception {
        var request = request().header("X-Tenant-ID", TENANT_ID);
        if (invalidToken) request.with(csrf().useInvalidToken());
        mvc.perform(request).andExpect(status().isForbidden());
        verifyNoInteractions(memberships);
        verifyNoCreationWork();
    }

    @Test
    void anonymousClientWithValidCsrfStillRequiresAuthentication() throws Exception {
        mvc.perform(request().with(anonymous()).with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(memberships);
        verifyNoCreationWork();
    }

    @Test
    void unsupportedContentTypeIsRejectedWithoutCreatingAKey() throws Exception {
        mvc.perform(request().with(csrf()).header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.TEXT_PLAIN).content("Producer"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoCreationWork();
    }

    @Test
    void getCannotCreateOrRevealAKey() throws Exception {
        mvc.perform(get(ROUTE).with(user(new AppUserDetails(account()))).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(memberships);
        verifyNoCreationWork();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void administratorRevokesOwnedKeyWithEmpty204AndCanRepeat(boolean alreadyRevoked) throws Exception {
        Tenant tenant = membership(TenantRole.ADMIN);
        ApiKey key = new ApiKey(tenant, "Producer", HASH, NOW.minusSeconds(120));
        if (alreadyRevoked) key.revoke(NOW.minusSeconds(60));
        Instant expected = alreadyRevoked ? NOW.minusSeconds(60) : NOW;
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(60));
        when(keys.findByIdAndTenant_IdForUpdate(KEY_ID, TENANT_ID)).thenReturn(Optional.of(key));

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(revocationRequest(KEY_ID).with(csrf()).header("X-Tenant-ID", TENANT_ID))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));
        }
        assertEquals(expected, key.getRevokedAt());
        verify(memberships, times(2)).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verify(keys, times(2)).findByIdAndTenant_IdForUpdate(KEY_ID, TENANT_ID);
        verifyNoInteractions(generator, hasher, tenants);
    }

    @Test
    void viewerCannotRevokeEvenWithForgedAdminHeader() throws Exception {
        membership(TenantRole.VIEWER);
        mvc.perform(revocationRequest(KEY_ID).with(csrf()).header("X-Tenant-ID", TENANT_ID)
                        .header("X-Tenant-Role", "ADMIN"))
                .andExpect(status().isForbidden());
        verifyNoCreationWork();
    }

    @Test
    void nonmemberCannotRevokeUsingAKeyIdOrForgedUserHeader() throws Exception {
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
        mvc.perform(revocationRequest(KEY_ID).with(csrf()).header("X-Tenant-ID", TENANT_ID)
                        .header("X-User-ID", KEY_ID))
                .andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoCreationWork();
    }

    @Test
    void foreignOrMissingKeyReturnsEmpty404() throws Exception {
        membership(TenantRole.ADMIN);
        when(keys.findByIdAndTenant_IdForUpdate(KEY_ID, TENANT_ID)).thenReturn(Optional.empty());
        mvc.perform(revocationRequest(KEY_ID).with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
        verify(keys).findByIdAndTenant_IdForUpdate(KEY_ID, TENANT_ID);
        verifyNoInteractions(clock, tenants, generator, hasher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void revocationRequiresAValidTenantHeaderBeforeLookup(String header) throws Exception {
        var request = revocationRequest(KEY_ID).with(csrf());
        if (!header.isEmpty()) request.header("X-Tenant-ID", header);
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(memberships);
        verifyNoCreationWork();
    }

    @Test
    void malformedKeyIdCannotReachRevocationLookup() throws Exception {
        mvc.perform(revocationRequest("not-a-uuid").with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(memberships);
        verifyNoCreationWork();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revocationRequiresValidCsrfBeforeLookup(boolean invalidToken) throws Exception {
        var request = revocationRequest(KEY_ID).header("X-Tenant-ID", TENANT_ID);
        if (invalidToken) request.with(csrf().useInvalidToken());
        mvc.perform(request).andExpect(status().isForbidden());
        verifyNoInteractions(memberships);
        verifyNoCreationWork();
    }

    @Test
    void anonymousClientCannotRevokeEvenWithValidCsrf() throws Exception {
        mvc.perform(revocationRequest(KEY_ID).with(anonymous()).with(csrf()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(memberships);
        verifyNoCreationWork();
    }

    @Test
    void getCannotRevokeAKey() throws Exception {
        mvc.perform(get(ROUTE + "/{id}/revoke", KEY_ID).with(user(new AppUserDetails(account())))
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(memberships);
        verifyNoCreationWork();
    }

    private MockHttpServletRequestBuilder revocationRequest(Object id) {
        return post(ROUTE + "/{id}/revoke", id).with(user(new AppUserDetails(account())));
    }

    private Tenant configureCreation() {
        Tenant tenant = membership(TenantRole.ADMIN);
        when(tenants.getReferenceById(TENANT_ID)).thenReturn(tenant);
        when(clock.instant()).thenReturn(NOW);
        when(generator.generate()).thenReturn(RAW);
        when(hasher.hash(RAW)).thenReturn(HASH);
        when(keys.save(any(ApiKey.class))).thenAnswer(call -> {
            ApiKey key = call.getArgument(0);
            ReflectionTestUtils.setField(key, "id", KEY_ID);
            return key;
        });
        return tenant;
    }

    private Tenant membership(TenantRole role) {
        Tenant tenant = new Tenant("Company A", NOW);
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID))
                .thenReturn(Optional.of(new TenantMembership(tenant, account(), role, NOW)));
        return tenant;
    }

    private AppUser account() {
        AppUser account = new AppUser("admin@example.com", "unused-test-hash", NOW);
        ReflectionTestUtils.setField(account, "id", USER_ID);
        return account;
    }

    private MockHttpServletRequestBuilder request() {
        return post(ROUTE).with(user(new AppUserDetails(account())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Producer\"}");
    }

    private void verifyNoCreationWork() {
        verifyNoInteractions(tenants, keys, generator, hasher, clock);
    }

    static Stream<String> validNames() {
        return Stream.of("Producer", "n".repeat(100), "Orders 東京");
    }

    static Stream<String> invalidBodies() {
        return Stream.of("{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"   \"}",
                "{\"name\":\"%s\"}".formatted("n".repeat(101)), "{", "", "null", "{\"name\":{}}");
    }
}
