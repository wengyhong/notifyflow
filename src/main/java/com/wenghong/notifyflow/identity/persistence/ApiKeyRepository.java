package com.wenghong.notifyflow.identity.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wenghong.notifyflow.identity.domain.ApiKey;

import jakarta.persistence.LockModeType;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Page<ApiKey> findAllByTenant_Id(UUID tenantId, Pageable pageable);

    // Hash + active status → authenticate a producer.
    Optional<ApiKey> findByKeyHashAndRevokedAtIsNull(String keyHash);

    // - Key ID + tenant ID → manage a tenant’s key.
    Optional<ApiKey> findByIdAndTenant_Id(UUID id, UUID tenantId);

    @Lock (LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select key from ApiKey key
        where key.id = :id
        and key.tenant.id = :tenantId
        """)
    Optional<ApiKey>findByIdAndTenant_IdForUpdate(

        @Param ("id") UUID id,
        @Param ("tenantId") UUID tenantId
    );
}
