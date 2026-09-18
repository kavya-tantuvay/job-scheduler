package com.jobscheduler.ratelimit;

import java.time.Duration;

/** Decides whether a job of a given type may start now, given cluster-wide per-type limits. */
public interface JobRateLimiter {

    /** @return true if the job may run now; false if its type is over its limit */
    boolean tryAcquire(String jobType);

    /** Roughly how long until the type can start another job; a sensible minimum deferral. */
    Duration refillInterval(String jobType);

    /** Used when rate limiting is disabled. */
    JobRateLimiter UNLIMITED = new JobRateLimiter() {
        @Override
        public boolean tryAcquire(String jobType) {
            return true;
        }

        @Override
        public Duration refillInterval(String jobType) {
            return Duration.ZERO;
        }
    };
}
