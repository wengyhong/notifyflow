package com.wenghong.notifyflow.delivery.application;

import java.time.Duration;
import java.util.Objects;

public record DeliveryRateLimitDecision(
        boolean allowed,
        Duration retryAfter) {

    public DeliveryRateLimitDecision {

        Objects.requireNonNull(retryAfter);

        if (allowed && !retryAfter.isZero()) {
            throw new IllegalArgumentException(
                    "An allowed decision must have zero retry delay");
        }

        if (!allowed && (retryAfter.isZero() || retryAfter.isNegative())) {
            throw new IllegalArgumentException(
                    "A denied decision must have a positive retry delay");
        }
    }
}
