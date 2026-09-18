package com.jobscheduler.worker.handlers;

import com.jobscheduler.worker.JobContext;
import com.jobscheduler.worker.JobHandler;
import com.jobscheduler.worker.NonRetryableJobException;
import org.springframework.stereotype.Component;

/**
 * Fails on purpose, to demonstrate retries, backoff and dead-lettering.
 *
 * <ul>
 *   <li>{@code {"failUntilAttempt": 3}} fails attempts 1 and 2, then succeeds on attempt 3</li>
 *   <li>{@code {"failUntilAttempt": 99}} keeps failing, so the job ends up DEAD</li>
 *   <li>{@code {"permanent": true}} throws a non-retryable error, so the job goes straight to FAILED</li>
 * </ul>
 */
@Component
public class FlakyJobHandler implements JobHandler {

    public static final String TYPE = "flaky";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(JobContext context) {
        if (context.payload().path("permanent").asBoolean(false)) {
            throw new NonRetryableJobException("Simulated permanent failure");
        }
        int failUntilAttempt = context.payload().path("failUntilAttempt").asInt(0);
        if (context.attempt() < failUntilAttempt) {
            throw new IllegalStateException("Simulated transient failure on attempt " + context.attempt());
        }
    }
}
