package com.wenghong.notifyflow.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.wenghong.notifyflow.identity.application.CurrentUserService;
import com.wenghong.notifyflow.identity.configuration.SecurityConfiguration;
import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantMembership;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.TenantMembershipRepository;
import com.wenghong.notifyflow.identity.security.AppUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CurrentUserController.class)
@Import({SecurityConfiguration.class, CurrentUserService.class})
class CurrentUserControllerTests {
    private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    @Autowired private MockMvc mvc;
    @MockitoBean private TenantMembershipRepository memberships;

    @Test
    void sessionUserGetsTheirIdentityAndPerWorkspaceRolesWithoutCsrfOrTenantHeader() throws Exception {
        AppUser account = account();
        Tenant first = tenant("Orders");
        Tenant second = tenant("Company");
        when(memberships.findAllByUser_Id(USER_ID)).thenReturn(List.of(
                new TenantMembership(first, account, TenantRole.ADMIN, NOW),
                new TenantMembership(second, account, TenantRole.VIEWER, NOW)));

        mvc.perform(get("/api/auth/me").with(user(new AppUserDetails(account))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.*", hasSize(3)))
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.workspaces", hasSize(2)))
                .andExpect(jsonPath("$.workspaces[0].*", hasSize(3)))
                .andExpect(jsonPath("$.workspaces[0].tenantId").value(first.getId().toString()))
                .andExpect(jsonPath("$.workspaces[0].name").value("Orders"))
                .andExpect(jsonPath("$.workspaces[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.workspaces[1].tenantId").value(second.getId().toString()))
                .andExpect(jsonPath("$.workspaces[1].role").value("VIEWER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        verify(memberships).findAllByUser_Id(USER_ID);
    }

    @Test
    void callerCannotSelectAnotherUserByQueryOrHeader() throws Exception {
        when(memberships.findAllByUser_Id(USER_ID)).thenReturn(List.of());
        mvc.perform(get("/api/auth/me").with(user(new AppUserDetails(account())))
                        .param("userId", UUID.randomUUID().toString())
                        .header("X-User-ID", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.workspaces", hasSize(0)));
        verify(memberships).findAllByUser_Id(USER_ID);
        verifyNoMoreInteractions(memberships);
    }

    @Test
    void userWithoutMembershipsGetsAnEmptyWorkspaceList() throws Exception {
        when(memberships.findAllByUser_Id(USER_ID)).thenReturn(List.of());
        mvc.perform(get("/api/auth/me").with(user(new AppUserDetails(account()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.workspaces", hasSize(0)));
    }

    @Test
    void anonymousUserIsRejectedBeforeReadingMemberships() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        verifyNoInteractions(memberships);
    }

    private AppUser account() {
        AppUser account = new AppUser("alice@example.com", "test-password-hash", NOW);
        ReflectionTestUtils.setField(account, "id", USER_ID);
        return account;
    }

    private Tenant tenant(String name) {
        Tenant tenant = new Tenant(name, NOW);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }
}
