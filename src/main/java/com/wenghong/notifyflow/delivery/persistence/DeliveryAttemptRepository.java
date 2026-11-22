package com.wenghong.notifyflow.delivery.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.wenghong.notifyflow.delivery.domain.DeliveryAttempt;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    @Query("""
            select a from DeliveryAttempt a
            where a.delivery.id = :deliveryId
              and a.delivery.event.tenant.id = :tenantId
              and a.delivery.endpoint.tenant.id = :tenantId
            """)
    Page<DeliveryAttempt> findHistoryByDeliveryIdAndTenantId(
            @Param("deliveryId") UUID deliveryId, @Param("tenantId") UUID tenantId, Pageable pageable);


    Optional<DeliveryAttempt> findFirstByDelivery_IdOrderByAttemptNumberDesc(UUID deliveryId);
}
