package com.wenghong.notifyflow.delivery.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wenghong.notifyflow.delivery.domain.Delivery;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

}
