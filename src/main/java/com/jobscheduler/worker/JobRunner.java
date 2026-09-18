package com.jobscheduler.worker;

import com.jobscheduler.job.ClaimedJob;
import com.jobscheduler.job.JobQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Executes one claimed job on a worker thread: looks up the handler, runs it outside any
 * database transaction, then records the outcome with a fenced status update.
 */
@Slf4j
@Component
public class JobRunner {

    static final int MAX_ERROR_LENGTH = 2_000;

    private final HandlerRegistry handlerRegistry;
    private final JobQueue jobQueue;

    public JobRunner(HandlerRegistry handlerRegistry, JobQueue jobQueue) {
        this.handlerRegistry = handlerRegistry;
        this.jobQueue = jobQueue;
    }

    public void run(ClaimedJob job) {
        Optional<JobHandler> handler = handlerRegistry.find(job.type());
        if (handler.isEmpty()) {
            // Only possible if a handler was removed while jobs of its type were still queued.
            recordFailure(job, "No handler registered for job type '" + job.type() + "'");
            return;
        }

        long startNanos = System.nanoTime();
        try {
            handler.get().handle(new JobContext(job.id(), job.type(), job.payload(), job.attempt(), job.maxAttempts()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Job {} type={} attempt {}/{} failed: {}",
                    job.id(), job.type(), job.attempt(), job.maxAttempts(), e.toString());
            recordFailure(job, describe(e));
            return;
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (jobQueue.markSucceeded(job)) {
            log.debug("Job {} type={} succeeded in {} ms", job.id(), job.type(), elapsedMs);
        } else {
            logLostClaim(job);
        }
    }

    private void recordFailure(ClaimedJob job, String error) {
        if (!jobQueue.markFailed(job, error)) {
            logLostClaim(job);
        }
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
