package com.wenghong.notifyflow.endpoint.api;

import java.time.Instant;
import java.util.UUID;

public record WebhookSecretRotatedResponse(UUID id, String signingSecret, Instant previousSigningSecretExpiresAt) {

    @Override
    public String toString() {
        return "WebhookSecretRotatedResponse[id=" + id + ", previousSigningSecretExpiresAt="
                + previousSigningSecretExpiresAt + "]";
    }
}
