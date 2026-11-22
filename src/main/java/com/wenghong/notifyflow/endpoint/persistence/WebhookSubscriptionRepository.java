package com.wenghong.notifyflow.endpoint.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wenghong.notifyflow.endpoint.domain.WebhookSubscription;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    List<WebhookSubscription> findAllByEndpoint_IdAndEndpoint_Tenant_IdOrderByEventTypeAscIdAsc(
            UUID endpointId, UUID tenantId);

    List<WebhookSubscription> findAllByEndpoint_Tenant_IdAndEventTypeAndEndpointEnabledTrue(
            UUID tenantId, String eventType);
}
