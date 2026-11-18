package com.wenghong.notifyflow.endpoint.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.wenghong.notifyflow.endpoint.application.WebhookEndpointCache;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointSnapshot;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RedisWebhookEndpointCache implements WebhookEndpointCache {
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public RedisWebhookEndpointCache(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public void put(WebhookEndpointSnapshot snapshot) {
        String key = "notifyflow:endpoint:" + snapshot.id();

        try {
            String value = json.writeValueAsString(snapshot);

            redis.opsForValue().set(key, value, Duration.ofSeconds(60));
        } catch (DataAccessException | JacksonException exception) {
            // Cache writes are optional; PostgreSQL remains authoritative.
        }
    }

    @Override
    public void evict(UUID id) {
        String key = "notifyflow:endpoint:" + id;
        try {
            redis.delete(key);
        } catch (DataAccessException exception) {
            // If eviction fails, the cached entry still expires after its TTL.
        }
    }

    @Override
    public Optional<WebhookEndpointSnapshot> findById(UUID id) {
        String key = "notifyflow:endpoint:" + id;

        try {
            String value = redis.opsForValue().get(key);

            if (value == null) {
                return Optional.empty();
            }

            return Optional.ofNullable(json.readValue(value, WebhookEndpointSnapshot.class));
        } catch (DataAccessException | JacksonException exception) {
            // Treat unavailable or unreadable cache data as a cache miss.
            return Optional.empty();
        }
    }
}
