package com.wenghong.notifyflow.identity.application;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wenghong.notifyflow.identity.persistence.TenantMembershipRepository;

@Service
public class TenantAccessService {

    private final TenantMembershipRepository memberships;

    public TenantAccessService(TenantMembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Transactional (readOnly = true)
    public TenantAccess requireAccess(UUID tenantId, UUID userId)
    {
        return memberships.findByTenant_IdAndUser_Id(tenantId, userId)
        .map(membership-> new TenantAccess(tenantId, membership.getRole())).
        orElseThrow(() ->
        new AccessDeniedException("Tenant access denied"));
    }
}
