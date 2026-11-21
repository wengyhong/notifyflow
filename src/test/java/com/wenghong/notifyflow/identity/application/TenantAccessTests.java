package com.wenghong.notifyflow.identity.application;

import java.util.UUID;

import com.wenghong.notifyflow.identity.domain.TenantRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantAccessTests {

    @Test
    void adminCanPerformAdministratorOperation() {
        assertDoesNotThrow(() -> new TenantAccess(UUID.randomUUID(), TenantRole.ADMIN).requireAdmin());
    }

    @Test
    void viewerCannotPerformAdministratorOperation() {
        assertThrows(AccessDeniedException.class,
                () -> new TenantAccess(UUID.randomUUID(), TenantRole.VIEWER).requireAdmin());
    }

    @Test
    void accessRequiresBothTenantAndRole() {
        assertThrows(NullPointerException.class, () -> new TenantAccess(null, TenantRole.ADMIN));
        assertThrows(NullPointerException.class, () -> new TenantAccess(UUID.randomUUID(), null));
    }
}
