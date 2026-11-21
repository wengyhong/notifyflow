package com.wenghong.notifyflow.identity.application;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyCreationResult(UUID id, String name, String rawKey, Instant createdAt) {

    @Override
    public String toString()
    {
        return "ApiKeyCreationResult[id=" + id + "]";
    }
}
