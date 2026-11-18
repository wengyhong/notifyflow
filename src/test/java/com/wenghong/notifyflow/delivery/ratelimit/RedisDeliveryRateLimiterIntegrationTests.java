package com.wenghong.notifyflow.delivery.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.wenghong.notifyflow.delivery.application.DeliveryRateLimitDecision;
import com.wenghong.notifyflow.delivery.configuration.DeliveryRateLimitConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class RedisDeliveryRateLimiterIntegrationTests {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.2.11-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory firstConnection;
    private static LettuceConnectionFactory secondConnection;
    private static StringRedisTemplate redis;
    private static StringRedisTemplate secondRedis;
    private final RedisScript<Long> script = new DeliveryRateLimitConfiguration().deliveryRateLimitScript();
    private RedisDeliveryRateLimiter limiter;

    @BeforeAll
    static void connectToIsolatedRedis() {
        firstConnection = connectionFactory();
        secondConnection = connectionFactory();
        redis = new StringRedisTemplate(firstConnection);
        secondRedis = new StringRedisTemplate(secondConnection);
        secondRedis.hasKey("warmup");
    }

    private static LettuceConnectionFactory connectionFactory() {
        var address = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        var client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ZERO)
                .build();
        var factory = new LettuceConnectionFactory(address, client);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    @AfterAll
    static void closeClients() {
        if (secondConnection != null) {
            secondConnection.destroy();
        }
        if (firstConnection != null) {
            firstConnection.destroy();
        }
    }

    @BeforeEach
    void resetIsolatedDatabase() {
        // This connection targets only this class's disposable Redis container.
        try (var connection = firstConnection.getConnection()) {
            connection.serverCommands().flushDb();
        }
        limiter = new RedisDeliveryRateLimiter(redis, script);
    }

    @Test
    void firstPermitCreatesCounterWithOneSecondExpiry() {
        UUID id = UUID.randomUUID();

        assertTrue(limiter.tryAcquire(id).allowed());

        assertEquals("1", redis.opsForValue().get(key(id)));
        Long ttl = redis.getExpire(key(id), TimeUnit.MILLISECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 1000);
    }

    @Test
    void fivePermitsAreAllowedAndFurtherCallsDoNotIncrementCounter() {
        UUID id = UUID.randomUUID();

        for (int index = 0; index < 5; index++) {
            assertTrue(limiter.tryAcquire(id).allowed());
        }
        for (int index = 0; index < 3; index++) {
            DeliveryRateLimitDecision denied = limiter.tryAcquire(id);
            assertFalse(denied.allowed());
            assertTrue(denied.retryAfter().compareTo(Duration.ZERO) > 0);
            assertTrue(denied.retryAfter().compareTo(Duration.ofSeconds(1)) <= 0);
        }
        assertEquals("5", redis.opsForValue().get(key(id)));
    }

    @Test
    void endpointsHaveIndependentCounters() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (int index = 0; index < 5; index++) {
            limiter.tryAcquire(first);
        }

        assertFalse(limiter.tryAcquire(first).allowed());
        assertTrue(limiter.tryAcquire(second).allowed());
        assertEquals("5", redis.opsForValue().get(key(first)));
        assertEquals("1", redis.opsForValue().get(key(second)));
    }

    @Test
    void expiredCounterStartsFreshWindow() {
        UUID id = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(id).allowed());

        await().atMost(Duration.ofSeconds(5)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key(id))));

        assertTrue(limiter.tryAcquire(id).allowed());
        assertEquals("1", redis.opsForValue().get(key(id)));
    }

    @Test
    void counterWithoutExpiryIsRepairedAndDenied() {
        UUID id = UUID.randomUUID();
        redis.opsForValue().set(key(id), "5");
        assertEquals(-1L, redis.getExpire(key(id), TimeUnit.MILLISECONDS));

        DeliveryRateLimitDecision decision = limiter.tryAcquire(id);

        assertFalse(decision.allowed());
        assertEquals(Duration.ofSeconds(1), decision.retryAfter());
        assertEquals("5", redis.opsForValue().get(key(id)));
        Long ttl = redis.getExpire(key(id), TimeUnit.MILLISECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 1000);
    }

    @Test
    void deniedCallsDoNotExtendExistingWindow() {
        UUID id = UUID.randomUUID();
        redis.opsForValue().set(key(id), "5", Duration.ofSeconds(5));
        Long before = redis.getExpire(key(id), TimeUnit.MILLISECONDS);

        for (int index = 0; index < 3; index++) {
            // A longer argument makes an accidental sliding-window reset observable.
            Long delay = redis.execute(script, List.of(key(id)), "5", "10000");
            assertNotNull(delay);
            assertTrue(delay > 0 && delay <= before);
        }

        assertTrue(redis.getExpire(key(id), TimeUnit.MILLISECONDS) <= before);
        assertEquals("5", redis.opsForValue().get(key(id)));
    }

    @Test
    void corruptCounterCausesConservativeDeferral() {
        UUID id = UUID.randomUUID();
        redis.opsForValue().set(key(id), "not-an-integer", Duration.ofSeconds(30));

        DeliveryRateLimitDecision decision = limiter.tryAcquire(id);

        assertFalse(decision.allowed());
        assertEquals(Duration.ofSeconds(1), decision.retryAfter());
        assertEquals("not-an-integer", redis.opsForValue().get(key(id)));
    }

    @Test
    void scriptCacheMissReloadsScriptWithoutResettingCounter() {
        UUID id = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(id).allowed());
        try (var connection = firstConnection.getConnection()) {
            connection.scriptingCommands().scriptFlush();
        }

        assertTrue(limiter.tryAcquire(id).allowed());
        assertEquals("2", redis.opsForValue().get(key(id)));
    }

    @Test
    void concurrentClientsShareExactlyFivePermits() throws Exception {
        UUID id = UUID.randomUUID();
        // Hold one window open long enough that rollover cannot affect the concurrency assertion.
        redis.opsForValue().set(key(id), "0", Duration.ofSeconds(30));
        var secondLimiter = new RedisDeliveryRateLimiter(secondRedis, script);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DeliveryRateLimitDecision>> pending = new ArrayList<>();
        List<DeliveryRateLimitDecision> decisions = new ArrayList<>();

        try (var workers = Executors.newFixedThreadPool(12)) {
            for (int index = 0; index < 40; index++) {
                var selected = index % 2 == 0 ? limiter : secondLimiter;
                pending.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return selected.tryAcquire(id);
                }));
            }
            start.countDown();
            for (var result : pending) {
                decisions.add(result.get(10, TimeUnit.SECONDS));
            }
        }

        assertEquals(5, decisions.stream().filter(DeliveryRateLimitDecision::allowed).count());
        assertEquals(35, decisions.stream().filter(decision -> !decision.allowed()).count());
        assertEquals("5", redis.opsForValue().get(key(id)));
        assertTrue(decisions.stream().filter(decision -> !decision.allowed())
                .allMatch(decision -> decision.retryAfter().compareTo(Duration.ZERO) > 0));
    }

    private String key(UUID id) {
        return "notifyflow:delivery:rate-limit:" + id;
    }
}
