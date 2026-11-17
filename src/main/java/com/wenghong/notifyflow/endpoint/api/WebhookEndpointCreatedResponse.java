package com.wenghong.notifyflow.endpoint.api;

import java.time.Instant;
import java.util.UUID;

public record WebhookEndpointCreatedResponse(

        UUID id,
        String name,
        String url,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        String signingSecret

) {

}
