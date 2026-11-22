package com.wenghong.notifyflow.identity.api;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyResponse(UUID id, String name, Instant createdAt, Instant revokedAt) {
}
