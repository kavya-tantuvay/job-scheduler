package com.jobscheduler.retry;

import com.jobscheduler.config.JobSchedulerProperties;
import com.jobscheduler.worker.NonRetryableJobException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Decides whether a failed attempt is retried, and after how long.
 *
 * <p><b>Exponential backoff</b> (2s, 4s, 8s, ... up to a cap) gives a struggling dependency
 * progressively more room to recover instead of hammering it at a fixed rate.
 *
 * <p><b>Jitter</b> spreads retries out. Without it, jobs that failed together (e.g. during the
 * same outage) retry at exactly the same moments and hit the dependency in synchronised waves.
 * This uses "equal jitter": the delay is uniform in {@code [ceiling/2, ceiling]}. Unlike "full
 * jitter" ({@code [0, ceiling]}) it guarantees a retry never fires almost immediately, while still
 * breaking up synchronised retries.
 */
@Component
public class RetryPolicy {

    private final long baseDelayMillis;
    private final long maxDelayMillis;
    private final DoubleSupplier random;

    @Autowired
    public RetryPolicy(JobSchedulerProperties properties) {
        this(properties.retry().baseDelay(), properties.retry().maxDelay(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    /** @param random source of values in {@code [0, 1)}; injectable for deterministic tests */
    RetryPolicy(Duration baseDelay, Duration maxDelay, DoubleSupplier random) {
        if (baseDelay.isNegative() || baseDelay.isZero() || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("Require 0 < baseDelay <= maxDelay");
        }
        this.baseDelayMillis = baseDelay.toMillis();
        this.maxDelayMillis = maxDelay.toMillis();
        this.random = random;
    }

    /**
     * @param attempt     1-based number of the attempt that just failed
     * @param maxAttempts total attempts allowed for the job
     */
    public RetryDecision decide(int attempt, int maxAttempts, Throwable failure) {
        if (failure instanceof NonRetryableJobException) {
            return new RetryDecision.Fail();
        }
        if (attempt >= maxAttempts) {
            return new RetryDecision.DeadLetter();
        }
        return new RetryDecision.Retry(backoff(attempt));
    }

    /** Delay before the attempt after {@code attempt}: uniform in [ceiling/2, ceiling]. */
    public Duration backoff(int attempt) {
        long ceiling = ceiling(attempt);
        long floor = ceiling / 2;
        return Duration.ofMillis(floor + (long) (random.getAsDouble() * (ceiling - floor + 1)));
    }

    /** {@code min(maxDelay, baseDelay * 2^(attempt-1))}, computed without overflow. */
    long ceiling(int attempt) {
        double exponential = baseDelayMillis * Math.pow(2, Math.max(0, attempt - 1));
        return (long) Math.min(maxDelayMillis, exponential);
    }
}
