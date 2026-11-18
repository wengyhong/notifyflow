package com.wenghong.notifyflow.event.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.wenghong.notifyflow.event.application.EventSnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class RedisEventIdempotencyCacheIntegrationTests {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.2.11-alpine")).withExposedPorts(6379);
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();
    private RedisEventIdempotencyCache cache;

    @BeforeAll
    static void connectToIsolatedRedis() {
        var address = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ZERO).build();
        factory = new LettuceConnectionFactory(address, client);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void closeClient() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void resetIsolatedDatabase() {
        // This client targets only this test class's disposable Redis container.
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        cache = new RedisEventIdempotencyCache(redis, json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"request-1", "request:with spaces/東京", "K"})
    void snapshotRoundTripsWithTwentyFourHourExpiry(String key) {
        EventSnapshot snapshot = snapshot();

        cache.put(key, snapshot);

        assertEquals(snapshot, cache.findByKey(key).orElseThrow());
        Long ttl = redis.getExpire(redisKey(key), TimeUnit.MILLISECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= Duration.ofHours(24).toMillis());
    }

    @Test
    void differentKeysHaveIndependentSnapshotsAndMissingKeyIsMiss() {
        EventSnapshot first = snapshot();
        EventSnapshot second = snapshot();
        cache.put("request-1", first);
        cache.put("request-2", second);

        assertEquals(first, cache.findByKey("request-1").orElseThrow());
        assertEquals(second, cache.findByKey("request-2").orElseThrow());
        assertTrue(cache.findByKey("missing").isEmpty());
    }

    @Test
    void readsDoNotExtendExpiry() {
        cache.put("request-1", snapshot());
        assertTrue(Boolean.TRUE.equals(redis.expire(redisKey("request-1"), Duration.ofSeconds(5))));
        long before = redis.getExpire(redisKey("request-1"), TimeUnit.MILLISECONDS);

        assertTrue(cache.findByKey("request-1").isPresent());

        long after = redis.getExpire(redisKey("request-1"), TimeUnit.MILLISECONDS);
        assertTrue(after > 0 && after <= before);
    }

    @Test
    void expiredSnapshotBecomesCacheMiss() {
        cache.put("request-1", snapshot());
        // Check the configured TTL above, then shorten this entry to avoid a 24-hour wait.
        assertTrue(Boolean.TRUE.equals(redis.expire(redisKey("request-1"), Duration.ofMillis(150))));

        await().atMost(Duration.ofSeconds(5))
                .until(() -> !Boolean.TRUE.equals(redis.hasKey(redisKey("request-1"))));

        assertTrue(cache.findByKey("request-1").isEmpty());
    }

    @Test
    void malformedJsonIsMissAndCanBeReplacedByValidSnapshot() {
        redis.opsForValue().set(redisKey("request-1"), "{bad-json", Duration.ofSeconds(60));
        assertTrue(cache.findByKey("request-1").isEmpty());
        EventSnapshot snapshot = snapshot();

        cache.put("request-1", snapshot);

        assertEquals(snapshot, cache.findByKey("request-1").orElseThrow());
    }

    @Test
    void redisWrongTypeErrorIsCacheMiss() {
        redis.opsForList().rightPush(redisKey("request-1"), "unexpected-list");

        assertTrue(cache.findByKey("request-1").isEmpty());
    }

    private EventSnapshot snapshot() {
        return new EventSnapshot(UUID.randomUUID(), "order.confirmed",
                json.readTree("{\"items\":[{\"quantity\":2}],\"note\":\"東京\"}"),
                Instant.parse("2026-11-18T00:00:00.123456Z"));
    }

    private String redisKey(String key) {
        return "notifyflow:event:idempotency:" + key;
    }
}
