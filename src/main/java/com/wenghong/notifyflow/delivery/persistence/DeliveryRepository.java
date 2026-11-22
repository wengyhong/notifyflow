package com.wenghong.notifyflow.delivery.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wenghong.notifyflow.delivery.domain.Delivery;
import com.wenghong.notifyflow.delivery.domain.DeliveryStatus;

import jakarta.persistence.LockModeType;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

        @Query("""
                        select d from Delivery d
                        where d.event.tenant.id = :tenantId
                          and d.endpoint.tenant.id = :tenantId
                          and (:status is null or d.status = :status)
                        """)
        Page<Delivery> findHistoryByTenantId(
                        @Param("tenantId") UUID tenantId,
                        @Param("status") DeliveryStatus status,
                        Pageable pageable);

        @Query("""
                        select d from Delivery d
                        where d.id = :id
                          and d.event.tenant.id = :tenantId
                          and d.endpoint.tenant.id = :tenantId
                        """)
        Optional<Delivery> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select d from Delivery d where d.id = :id")
        Optional<Delivery> findByIdForUpdate(@Param("id") UUID id);

        @Query("""
                        select d.id
                        from Delivery d
                        where d.status = :status
                        and d.nextAttemptAt  <= :now
                        order by d.nextAttemptAt , d.id
                        """)
        List<UUID> findDueRetryIds(@Param("status") DeliveryStatus status,
                        @Param("now") Instant now,
                        Pageable pageable);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("""
                        select d from Delivery d
                        where d.id = :id
                          and d.event.tenant.id = :tenantId
                          and d.endpoint.tenant.id = :tenantId
                        """)
        Optional<Delivery> findByIdAndTenantIdForUpdate(
                        @Param("id") UUID id,
                        @Param("tenantId") UUID tenantId);
}
