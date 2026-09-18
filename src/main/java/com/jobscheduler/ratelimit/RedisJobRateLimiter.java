package com.jobscheduler.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Token-bucket limiter shared by every instance: one bucket per job type, stored in Redis.
 *
 * <p>Each bucket holds up to N tokens and refills at N tokens/second, where N is the type's
 * per-second limit. Starting a job takes a token. That caps the sustained rate at N/s across the
 * cluster while allowing a burst of at most N after an idle period. A fixed one-second window
 * counter is simpler but lets up to 2N through across a window boundary.
 *
 * <p>The Lua script runs atomically inside Redis, so workers on different machines can't both see
 * the last token as available. It reads Redis's own clock ({@code TIME}), so instances with skewed
 * clocks still refill buckets consistently.
 *
 * <p>If Redis is unavailable the limiter <b>fails open</b> (the job runs): a rate limiter should
 * protect a downstream system, not become a second way for the whole queue to stall.
 */
@Slf4j
public class RedisJobRateLimiter implements JobRateLimiter {

    private static final RedisScript<Long> TAKE_TOKEN = new DefaultRedisScript<>("""
            redis.replicate_commands() -- needed on Redis < 7 to write after reading TIME
            local rate = tonumber(ARGV[1])
            local capacity = rate
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1]) or capacity
            local last = tonumber(state[2]) or now
            tokens = math.min(capacity, tokens + math.max(0, now - last) * rate / 1000)
            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], 2000 + math.ceil(1000 * capacity / rate))
            return allowed
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Map<String, Integer> limitsPerSecond;

    public RedisJobRateLimiter(StringRedisTemplate redis, Map<String, Integer> limitsPerSecond) {
        this.redis = redis;
        this.limitsPerSecond = Map.copyOf(limitsPerSecond);
        log.info("Redis rate limiting enabled, per-second limits: {}", this.limitsPerSecond);
    }

    @Override
    public boolean tryAcquire(String jobType) {
        Integer limit = limitsPerSecond.get(jobType);
        if (limit == null) {
            return true;
        }
        try {
            Long allowed = redis.execute(TAKE_TOKEN, List.of("jobs:rate:" + jobType), String.valueOf(limit));
            return allowed == null || allowed == 1L;
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing job of type '{}': {}", jobType, e.toString());
            return true;
        }
    }

    @Override
    public Duration refillInterval(String jobType) {
        Integer limit = limitsPerSecond.get(jobType);
        return limit == null ? Duration.ZERO : Duration.ofMillis(Math.max(1, 1000 / limit));
    }
}
