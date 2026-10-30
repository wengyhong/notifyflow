package com.wenghong.notifyflow.endpoint.api;

import java.util.UUID;

public record WebhookSubscriptionResponse(

    UUID id,
    UUID endpointId,
    String eventType
) {

}
