package com.wenghong.notifyflow.identity.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wenghong.notifyflow.identity.domain.TenantMembership;

public interface TenantMembershipRepository extends JpaRepository<TenantMembership, UUID> {

    List<TenantMembership> findAllByUser_Id(UUID userId);

    Optional<TenantMembership> findByTenant_IdAndUser_Id(UUID tenantId, UUID userId);
}
