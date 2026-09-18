package com.jobscheduler.worker.handlers;

import com.jobscheduler.worker.JobContext;
import com.jobscheduler.worker.JobHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Simulates a slow job: sleeps, then succeeds. Payload: {@code {"durationMs": 2000}}
 * (default 1000, capped at 60000). Useful for watching concurrency and graceful shutdown.
 */
@Slf4j
@Component
public class SleepJobHandler implements JobHandler {

    public static final String TYPE = "sleep";
    static final long DEFAULT_DURATION_MS = 1_000;
    static final long MAX_DURATION_MS = 60_000;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(JobContext context) throws InterruptedException {
        long requested = context.payload().path("durationMs").asLong(DEFAULT_DURATION_MS);
        long durationMs = Math.max(0, Math.min(requested, MAX_DURATION_MS));
        log.debug("Job {} sleeping for {} ms", context.jobId(), durationMs);
        Thread.sleep(durationMs);
    }
}
