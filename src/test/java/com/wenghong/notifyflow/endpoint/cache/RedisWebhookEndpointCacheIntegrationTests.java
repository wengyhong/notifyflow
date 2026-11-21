package com.wenghong.notifyflow.endpoint.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.wenghong.notifyflow.endpoint.application.WebhookEndpointSnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class RedisWebhookEndpointCacheIntegrationTests {

    private static final UUID TENANT_ID = UUID.fromString("e8d0c4f9-28df-4070-a6a4-052a05c68236");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.2.11-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();
    private RedisWebhookEndpointCache cache;

    @BeforeAll
    static void connectToIsolatedRedis() {
        var address = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        var client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO).build();
        connectionFactory = new LettuceConnectionFactory(address, client);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void closeClient() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void resetIsolatedDatabase() {
        // This client connects only to this test class's disposable container.
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        cache = new RedisWebhookEndpointCache(redis, json);
    }

    @Test
    void snapshotRoundTripsThroughRedisWithSixtySecondTtl() {
        WebhookEndpointSnapshot snapshot = snapshot(UUID.randomUUID(), true);

        cache.put(snapshot);

        assertEquals(snapshot, cache.findById(snapshot.id()).orElseThrow());
        String raw = redis.opsForValue().get(key(snapshot.id()));
        assertNotNull(raw);
        assertEquals(7, json.readTree(raw).size());
        assertEquals(TENANT_ID.toString(), json.readTree(raw).get("tenantId").asString());
        Long ttl = redis.getExpire(key(snapshot.id()), TimeUnit.MILLISECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 60_000);
    }

    @Test
    void missingKeyReturnsEmpty() {
        assertTrue(cache.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void evictRemovesOneEndpointAndPreservesAnother() {
        WebhookEndpointSnapshot first = snapshot(UUID.randomUUID(), true);
        WebhookEndpointSnapshot second = snapshot(UUID.randomUUID(), false);
        cache.put(first);
        cache.put(second);

        cache.evict(first.id());
        cache.evict(first.id());

        assertTrue(cache.findById(first.id()).isEmpty());
        assertEquals(second, cache.findById(second.id()).orElseThrow());
    }

    @Test
    void expiredEntryBecomesCacheMiss() {
        WebhookEndpointSnapshot snapshot = snapshot(UUID.randomUUID(), true);
        cache.put(snapshot);
        // The configured 60-second TTL is checked above; shorten only this test entry.
        assertTrue(Boolean.TRUE.equals(redis.expire(key(snapshot.id()), Duration.ofMillis(150))));

        await().atMost(Duration.ofSeconds(5))
                .until(() -> !Boolean.TRUE.equals(redis.hasKey(key(snapshot.id()))));

        assertTrue(cache.findById(snapshot.id()).isEmpty());
    }

    @Test
    void putReplacesExistingSnapshot() {
        UUID id = UUID.randomUUID();
        cache.put(snapshot(id, true));
        WebhookEndpointSnapshot updated = snapshot(id, false);

        cache.put(updated);

        assertEquals(updated, cache.findById(id).orElseThrow());
    }

    @Test
    void malformedJsonIsCacheMissAndCanBeReplaced() {
        WebhookEndpointSnapshot snapshot = snapshot(UUID.randomUUID(), true);
        redis.opsForValue().set(key(snapshot.id()), "{broken-json", Duration.ofSeconds(60));

        assertTrue(cache.findById(snapshot.id()).isEmpty());
        cache.put(snapshot);

        assertEquals(snapshot, cache.findById(snapshot.id()).orElseThrow());
    }

    @Test
    void redisWrongTypeReadFailureBecomesCacheMiss() {
        UUID id = UUID.randomUUID();
        redis.opsForList().rightPush(key(id), "unexpected-list");

        assertTrue(cache.findById(id).isEmpty());
    }

    private WebhookEndpointSnapshot snapshot(UUID id, boolean enabled) {
        Instant created = Instant.parse("2026-11-18T00:00:00.123456Z");
        return new WebhookEndpointSnapshot(id, TENANT_ID, "Warehouse 東京", "https://example.com/webhook",
                enabled, created, created.plusSeconds(10));
    }

    private String key(UUID id) {
        return "notifyflow:endpoint:" + id;
    }
}
