package com.wenghong.notifyflow.delivery.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wenghong.notifyflow.delivery.domain.Delivery;

import jakarta.persistence.LockModeType;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.id = :id")
    Optional<Delivery> findByIdForUpdate(@Param("id") UUID id);
}
