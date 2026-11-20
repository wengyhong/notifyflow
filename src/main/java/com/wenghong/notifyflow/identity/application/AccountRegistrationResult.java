package com.wenghong.notifyflow.identity.application;

import java.util.UUID;

public record AccountRegistrationResult(
        UUID userId,
        UUID tenantId
) {
}