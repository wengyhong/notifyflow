package com.wenghong.notifyflow.event.cache;

import java.time.Duration;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.wenghong.notifyflow.event.application.EventIdempotencyCache;
import com.wenghong.notifyflow.event.application.EventSnapshot;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import java.util.Optional;
@Component
public class RedisEventIdempotencyCache implements EventIdempotencyCache {
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public RedisEventIdempotencyCache(
            StringRedisTemplate redis,
            JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public void put(String idempotencyKey, EventSnapshot snapshot) {
        String key = "notifyflow:event:idempotency:" + idempotencyKey;

        try {
            String value = json.writeValueAsString(snapshot);
            redis.opsForValue().set(key, value, Duration.ofHours(24));
        } catch (DataAccessException | JacksonException exception) {
            // Cache writes are optional; PostgreSQL preserves idempotency.
        }
    }

    public Optional<EventSnapshot> findByKey(String idempotencyKey) {
    String key = "notifyflow:event:idempotency:" + idempotencyKey;

    try {
        String value = redis.opsForValue().get(key);

        if (value == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(
                json.readValue(value, EventSnapshot.class));
    } catch (DataAccessException | JacksonException exception) {
        // Unavailable or unreadable cache data falls back to PostgreSQL.
        return Optional.empty();
    }
}
}
