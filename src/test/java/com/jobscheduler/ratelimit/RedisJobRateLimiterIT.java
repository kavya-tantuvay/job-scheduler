package com.jobscheduler.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limiter against a real Redis (Testcontainers, or {@code TEST_REDIS_HOST}/{@code TEST_REDIS_PORT}).
 * Each test uses fresh job types, so buckets never leak between tests.
 */
class RedisJobRateLimiterIT {

    private static GenericContainer<?> redisContainer;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        String host = System.getenv("TEST_REDIS_HOST");
        int port;
        if (host == null) {
            redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
            redisContainer.start();
            host = redisContainer.getHost();
            port = redisContainer.getMappedPort(6379);
        } else {
            port = Integer.parseInt(System.getenv().getOrDefault("TEST_REDIS_PORT", "6379"));
        }
        connectionFactory = new LettuceConnectionFactory(host, port);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    private static String freshType() {
        return "t" + UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    void concurrentWorkersCannotExceedTheBurstCapacity() throws Exception {
        String type = freshType();
        JobRateLimiter limiter = new RedisJobRateLimiter(redis, Map.of(type, 5));
        int workers = 10;
        CyclicBarrier startTogether = new CyclicBarrier(workers);
        ExecutorService threads = Executors.newFixedThreadPool(workers);
        List<Future<Integer>> results = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < workers; i++) {
            results.add(threads.submit(() -> {
                startTogether.await();
                int allowed = 0;
                for (int j = 0; j < 5; j++) {
                    if (limiter.tryAcquire(type)) {
                        allowed++;
                    }
                }
                return allowed;
            }));
        }
        int allowed = 0;
        for (Future<Integer> result : results) {
            allowed += result.get(30, TimeUnit.SECONDS);
        }
        double elapsedSeconds = (System.nanoTime() - start) / 1e9;
        threads.shutdown();

        // 50 attempts: a full bucket (5) plus whatever refilled while the test ran (5/s).
        assertThat(allowed).isBetween(5, 5 + (int) Math.ceil(elapsedSeconds * 5));
    }

    @Test
    void bucketRefillsAtTheConfiguredRate() throws Exception {
        String type = freshType();
        JobRateLimiter limiter = new RedisJobRateLimiter(redis, Map.of(type, 10));
        while (limiter.tryAcquire(type)) {
            // drain the initial burst
        }

        Thread.sleep(500); // ~5 tokens at 10/s

        int allowed = 0;
        while (limiter.tryAcquire(type)) {
            allowed++;
        }
        assertThat(allowed).isBetween(4, 6);
    }

    @Test
    void typesHaveIndependentBucketsAndUnlistedTypesAreUnlimited() {
        String limited = freshType();
        String other = freshType();
        JobRateLimiter limiter = new RedisJobRateLimiter(redis, Map.of(limited, 1, other, 1));

        assertThat(limiter.tryAcquire(limited)).isTrue();
        assertThat(limiter.tryAcquire(limited)).isFalse();
        assertThat(limiter.tryAcquire(other)).isTrue();
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("unlisted")).isTrue();
        }
    }

    @Test
    void failsOpenWhenRedisIsUnreachable() {
        LettuceConnectionFactory deadFactory = new LettuceConnectionFactory("127.0.0.1", 1);
        deadFactory.afterPropertiesSet();
        try {
            JobRateLimiter limiter = new RedisJobRateLimiter(new StringRedisTemplate(deadFactory), Map.of("webhook", 1));
            assertThat(limiter.tryAcquire("webhook")).isTrue();
            assertThat(limiter.tryAcquire("webhook")).isTrue();
        } finally {
            deadFactory.destroy();
        }
    }
}
