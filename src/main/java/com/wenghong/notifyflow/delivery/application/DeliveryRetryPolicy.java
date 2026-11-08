package com.wenghong.notifyflow.delivery.application;

import java.time.Duration;
import java.util.random.RandomGenerator;

import org.springframework.stereotype.Component;

@Component
public class DeliveryRetryPolicy {

    private static final int MAX_ATTEMPTS = 5;

    private final RandomGenerator random;

    public DeliveryRetryPolicy(RandomGenerator random) {
        this.random = random;
    }

    // 408: request timeout.
    // 429: too many requests.
    // 5xx: server errors.
    public boolean isRetryableStatus(int responseStatus) {
        return responseStatus == 408 || responseStatus == 429 || (responseStatus >= 500 && responseStatus <= 599);
    }

    public boolean canRetry(int attemptNumber) {
        if (attemptNumber < 1)
            throw new IllegalArgumentException("Attempts must be positive");

        return attemptNumber < MAX_ATTEMPTS;
    }

    public Duration retryDelay(int attemptNumber) {
        if (!canRetry(attemptNumber))
            throw new IllegalArgumentException("No retries remain");

        Duration delay = Duration.ofSeconds(5);

        for (int i = 1; i < attemptNumber; i++) {
            delay = delay.multipliedBy(2);
        }

        long maxJitterMillis = delay.toMillis() / 5;
        long jitterMillis = random.nextLong(maxJitterMillis + 1);

        return delay.plusMillis(jitterMillis);
    }

}
