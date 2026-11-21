package com.wenghong.notifyflow.identity.api;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyCreatedResponse(
    UUID id,
    String name,
    String rawKey,
    Instant createdAt
) {

    @Override
    public String toString()
    {
        return "ApiKeyCreatedResponse[id=" + id + "]";
    }
}
