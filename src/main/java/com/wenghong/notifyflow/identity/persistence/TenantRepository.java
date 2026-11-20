package com.wenghong.notifyflow.identity.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wenghong.notifyflow.identity.domain.Tenant;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

}
