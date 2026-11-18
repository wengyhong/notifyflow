package com.wenghong.notifyflow.delivery.application;

import java.util.UUID;

public interface DeliveryRateLimiter {

    DeliveryRateLimitDecision tryAcquire(UUID endpointId);
}
