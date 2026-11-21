package com.wenghong.notifyflow.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantMembership;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.TenantMembershipRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TenantAccessServiceTests {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private final TenantMembershipRepository memberships = mock(TenantMembershipRepository.class);
    private final TenantAccessService service = new TenantAccessService(memberships);

    @ParameterizedTest
    @EnumSource(TenantRole.class)
    void returnsRoleFromMatchingMembership(TenantRole role) {
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        TenantMembership membership = new TenantMembership(new Tenant("Company A", now),
                new AppUser("alice@example.com", "test-hash", now), role, now);
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID)).thenReturn(Optional.of(membership));

        assertEquals(new TenantAccess(TENANT_ID, role), service.requireAccess(TENANT_ID, USER_ID));

        verify(memberships).findByTenant_IdAndUser_Id(TENANT_ID, USER_ID);
        verifyNoMoreInteractions(memberships);
    }

    @Test
    void missingMembershipIsDenied() {
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class, () -> service.requireAccess(TENANT_ID, USER_ID));
    }

    @Test
    void databaseOutageDoesNotBecomeAnAuthorizationDecision() {
        var failure = new DataAccessResourceFailureException("Test database outage");
        when(memberships.findByTenant_IdAndUser_Id(TENANT_ID, USER_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.requireAccess(TENANT_ID, USER_ID)));
    }
}
