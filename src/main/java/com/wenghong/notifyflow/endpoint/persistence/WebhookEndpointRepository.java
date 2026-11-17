package com.wenghong.notifyflow.endpoint.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;

import jakarta.persistence.LockModeType;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select endpoint from WebhookEndpoint endpoint where endpoint.id = :id")
    Optional<WebhookEndpoint> findByIdForUpdate(@Param("id") UUID id);
}
