package com.jobscheduler.config;

import com.jobscheduler.ratelimit.JobRateLimiter;
import com.jobscheduler.ratelimit.RedisJobRateLimiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Uses Redis for per-type limits when {@code jobs.rate-limit.enabled=true}; otherwise no limits and no Redis. */
@Configuration
public class RateLimitConfig {

    @Bean
    public JobRateLimiter jobRateLimiter(JobSchedulerProperties properties,
                                         ObjectProvider<StringRedisTemplate> redisTemplate) {
        JobSchedulerProperties.RateLimit rateLimit = properties.rateLimit();
        if (!rateLimit.enabled() || rateLimit.perSecond().isEmpty()) {
            return JobRateLimiter.UNLIMITED;
        }
        return new RedisJobRateLimiter(redisTemplate.getObject(), rateLimit.perSecond());
    }
}
