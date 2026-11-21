package com.wenghong.notifyflow.event.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wenghong.notifyflow.event.domain.Event;

public interface EventRepository extends JpaRepository<Event, UUID> {

    Optional<Event> findByTenant_IdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

}
