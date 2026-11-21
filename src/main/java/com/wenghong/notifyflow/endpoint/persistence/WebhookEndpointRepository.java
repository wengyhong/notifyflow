package com.wenghong.notifyflow.endpoint.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;

import jakarta.persistence.LockModeType;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    Optional<WebhookEndpoint> findByIdAndTenant_Id(UUID id, UUID tenantId);

    Page<WebhookEndpoint> findAllByTenant_Id(
            UUID tenantId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select endpoint from WebhookEndpoint endpoint
            where endpoint.id = :id
              and endpoint.tenant.id = :tenantId
            """)
    Optional<WebhookEndpoint> findByIdAndTenantIdForUpdate(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId);
}
