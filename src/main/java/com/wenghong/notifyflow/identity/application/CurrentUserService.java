package com.wenghong.notifyflow.identity.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wenghong.notifyflow.identity.api.WorkspaceResponse;
import com.wenghong.notifyflow.identity.persistence.TenantMembershipRepository;

@Service
public class CurrentUserService {

    private final TenantMembershipRepository memberships;

    public CurrentUserService(TenantMembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceResponse> findWorkspaces(UUID userId) {
        return memberships.findAllByUser_Id(userId).stream()
                .map(membership -> new WorkspaceResponse(
                        membership.getTenant().getId(),
                        membership.getTenant().getName(),
                        membership.getRole()))
                .toList();
    }
}
