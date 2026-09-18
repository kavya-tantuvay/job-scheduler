package com.jobscheduler.worker;

import com.jobscheduler.job.ClaimedJob;
import com.jobscheduler.job.JobQueue;
import com.jobscheduler.job.JobStatus;
import com.jobscheduler.retry.RetryDecision;
import com.jobscheduler.retry.RetryPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Executes one claimed job on a worker thread: looks up the handler, runs it outside any
 * database transaction, then records the outcome with a fenced status update. Failures go
 * through the {@link RetryPolicy}: retry with backoff, dead-letter, or fail permanently.
 */
@Slf4j
@Component
public class JobRunner {

    static final int MAX_ERROR_LENGTH = 2_000;

    private final HandlerRegistry handlerRegistry;
    private final JobQueue jobQueue;
    private final RetryPolicy retryPolicy;

    public JobRunner(HandlerRegistry handlerRegistry, JobQueue jobQueue, RetryPolicy retryPolicy) {
        this.handlerRegistry = handlerRegistry;
        this.jobQueue = jobQueue;
        this.retryPolicy = retryPolicy;
    }

    public void run(ClaimedJob job) {
        Optional<JobHandler> handler = handlerRegistry.find(job.type());
        if (handler.isEmpty()) {
            // Only possible if a handler was removed while jobs of its type were still queued.
            handleFailure(job, new NonRetryableJobException("No handler registered for job type '" + job.type() + "'"));
            return;
        }

        long startNanos = System.nanoTime();
        try {
            handler.get().handle(new JobContext(job.id(), job.type(), job.payload(), job.attempt(), job.maxAttempts()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            handleFailure(job, e);
            return;
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (jobQueue.markSucceeded(job)) {
            log.debug("Job {} type={} succeeded in {} ms", job.id(), job.type(), elapsedMs);
        } else {
            logLostClaim(job);
        }
    }

    private void handleFailure(ClaimedJob job, Exception failure) {
        RetryDecision decision = retryPolicy.decide(job.attempt(), job.maxAttempts(), failure);
        Optional<JobStatus> next = jobQueue.recordFailure(job, describe(failure), decision);
        if (next.isEmpty()) {
            logLostClaim(job);
            return;
        }
        String outcome = decision instanceof RetryDecision.Retry retry
                ? "retrying in " + retry.delay().toMillis() + " ms"
                : "moved to " + next.get();
        log.warn("Job {} type={} attempt {}/{} failed ({}): {}",
                job.id(), job.type(), job.attempt(), job.maxAttempts(), outcome, failure.toString());
    }

    private static void logLostClaim(ClaimedJob job) {
        log.warn("Job {} attempt {} no longer owned by {}; its lease probably expired and it was "
                + "reclaimed. Result discarded.", job.id(), job.attempt(), job.workerId());
    }

    /** "ExceptionClass: message", plus the root cause if different, truncated to fit the column comfortably. */
    static String describe(Throwable error) {
        StringBuilder text = new StringBuilder(error.toString());
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root != error) {
            text.append(" (root cause: ").append(root).append(')');
        }
        return text.length() <= MAX_ERROR_LENGTH ? text.toString() : text.substring(0, MAX_ERROR_LENGTH);
    }
}
