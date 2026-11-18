package com.wenghong.notifyflow.endpoint.application;

import java.time.Instant;
import java.util.UUID;

import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;

public record WebhookEndpointSnapshot(
UUID id,
String name,
String url,
boolean enabled,
Instant createdAt,
Instant updatedAt


) {

    public static WebhookEndpointSnapshot from(WebhookEndpoint endpoint)
    {
         return new WebhookEndpointSnapshot(
            endpoint.getId(),
            endpoint.getName(),
            endpoint.getUrl(),
            endpoint.isEnabled(),
            endpoint.getCreatedAt(),
            endpoint.getUpdatedAt()
    );
    }
}
