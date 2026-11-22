package com.wenghong.notifyflow.identity.api;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    void listReturnsMetadataOnlyIncludingActiveAndRevokedKeysWithoutCsrf() throws Exception {
        Tenant tenant = membership(TenantRole.ADMIN);
        ApiKey active = new ApiKey(tenant, "Active producer", HASH, NOW);
        ReflectionTestUtils.setField(active, "id", KEY_ID);
        ApiKey revoked = new ApiKey(tenant, "Revoked producer", "c".repeat(64), NOW.minusSeconds(60));
        UUID revokedId = UUID.randomUUID();
        ReflectionTestUtils.setField(revoked, "id", revokedId);
        revoked.revoke(NOW);
        when(keys.findAllByTenant_Id(eq(TENANT_ID), any(Pageable.class)))
                .thenAnswer(call -> new PageImpl<>(List.of(active, revoked), call.getArgument(1), 2));

        mvc.perform(listRequest().header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].*", hasSize(4)))
                .andExpect(jsonPath("$.content[0].id").value(KEY_ID.toString()))
                .andExpect(jsonPath("$.content[0].name").value("Active producer"))
                .andExpect(jsonPath("$.content[0].createdAt").value(NOW.toString()))
                .andExpect(jsonPath("$.content[0].revokedAt").isEmpty())
                .andExpect(jsonPath("$.content[1].id").value(revokedId.toString()))
                .andExpect(jsonPath("$.content[1].revokedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.content[0].rawKey").doesNotExist())
                .andExpect(jsonPath("$.content[0].keyHash").doesNotExist())
                .andExpect(jsonPath("$.content[0].tenant").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1));

        var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(keys).findAllByTenant_Id(eq(TENANT_ID), pageable.capture());
        assertEquals(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id")), pageable.getValue());
        verifyNoInteractions(tenants, generator, hasher, clock);
    }

    @Test
    void listSupportsExplicitPaginationAndEmptyPage() throws Exception {
        membership(TenantRole.ADMIN);
        when(keys.findAllByTenant_Id(eq(TENANT_ID), any(Pageable.class)))
                .thenAnswer(call -> new PageImpl<ApiKey>(List.of(), call.getArgument(1), 4));
        mvc.perform(listRequest().header("X-Tenant-ID", TENANT_ID).param("page", "3").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page").value(3))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void listReturnsEmptyPageForWorkspaceWithoutKeys() throws Exception {
        membership(TenantRole.ADMIN);
        when(keys.findAllByTenant_Id(eq(TENANT_ID), any(Pageable.class)))
                .thenAnswer(call -> Page.empty(call.getArgument(1)));
        mvc.perform(listRequest().header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void viewerCannotListKeysEvenWithForgedAdminHeader() throws Exception {
        membership(TenantRole.VIEWER);
        mvc.perform(listRequest().header("X-Tenant-ID", TENANT_ID).header("X-Tenant-Role", "ADMIN"))
                .andExpect(status().isForbidden());
        verifyNoCreationWork();
    }

    @Test
    void listRejectsNonMemberBeforeKeyQuery() throws Exception {
        mvc.perform(listRequest().header("X-Tenant-ID", TENANT_ID)).andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoCreationWork();
    }

    @Test
    void listCannotBorrowMembershipFromAnotherTenant() throws Exception {
        membership(TenantRole.ADMIN);
        UUID otherTenant = UUID.randomUUID();
        mvc.perform(listRequest().header("X-Tenant-ID", otherTenant)).andExpect(status().isForbidden());
        verify(memberships).findByTenant_IdAndUser_Id(otherTenant, USER_ID);
        verifyNoCreationWork();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void listRejectsMissingOrMalformedTenantHeader(String value) throws Exception {
        var request = listRequest();
        if (!value.isEmpty()) request.header("X-Tenant-ID", value);
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(memberships);
        verifyNoCreationWork();
    }

    @Test
    void listRequiresAnAuthenticatedUser() throws Exception {
        mvc.perform(listRequest().with(anonymous()).header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(memberships);
        verifyNoCreationWork();
    }

    private MockHttpServletRequestBuilder listRequest() {
        return get(ROUTE).with(user(new AppUserDetails(account())));
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
