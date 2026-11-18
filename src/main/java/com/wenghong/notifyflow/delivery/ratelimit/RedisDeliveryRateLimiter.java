package com.wenghong.notifyflow.delivery.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.wenghong.notifyflow.delivery.application.DeliveryRateLimitDecision;
import com.wenghong.notifyflow.delivery.application.DeliveryRateLimiter;

@Component
public class RedisDeliveryRateLimiter implements DeliveryRateLimiter {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> script;

    public RedisDeliveryRateLimiter(StringRedisTemplate redis,

            @Qualifier("deliveryRateLimitScript") RedisScript<Long> script) {
        this.redis = redis;
        this.script = script;
    }

    @Override
    public DeliveryRateLimitDecision tryAcquire(UUID endpointId) {
        Objects.requireNonNull(endpointId);

        String key = "notifyflow:delivery:rate-limit:" + endpointId;

        try {

            Long waitMillis = redis.execute(script, List.of(key), "5", "1000");

            if (waitMillis != null && waitMillis >= 0) {
                return new DeliveryRateLimitDecision(waitMillis == 0, Duration.ofMillis(waitMillis));

            }
        } catch (DataAccessException exception) {

        }

        return new DeliveryRateLimitDecision(false, Duration.ofSeconds(1));

    }

}
