package com.wenghong.notifyflow.delivery.application;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record DeliveryWork(

    UUID id,
    UUID attemptId,
    String url,
    JsonNode payload,
    String signingSecret,
    String previousSigningSecret,
    Instant previousSigningSecretExpiresAt
) {

    @Override
    public String toString()
    {
        return "DeliveryWork[id=" + id + ", attemptID=" + attemptId + "]";
    }
}
