package com.wenghong.notifyflow.delivery.messaging.outbox;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DeliveryOutboxRepository extends JpaRepository<DeliveryOutboxMessage, UUID> {


    @Query (value="""

        SELECT *
        from delivery_outbox
        WHERE published_at IS NULL
        ORDER BY created_at, id
        LIMIT 1
        FOR UPDATE SKIP LOCKED

        """,nativeQuery = true)
    Optional<DeliveryOutboxMessage> findNextUnpublishedForUpdate();
}
