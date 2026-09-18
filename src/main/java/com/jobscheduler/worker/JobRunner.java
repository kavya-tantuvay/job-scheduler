package com.jobscheduler.worker;

import com.jobscheduler.job.AttemptFailure;
import com.jobscheduler.job.ClaimedJob;
import com.jobscheduler.job.JobQueue;
import com.jobscheduler.job.JobStatus;
import com.jobscheduler.metrics.JobMetrics;
import com.jobscheduler.ratelimit.JobRateLimiter;
import com.jobscheduler.retry.RetryDecision;
import com.jobscheduler.retry.RetryPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Executes one claimed job on a worker thread: looks up the handler, runs it outside any
 * database transaction, then records the outcome with a fenced status update. Failures go
 * through the {@link RetryPolicy}: retry with backoff, dead-letter, or fail permanently.
 */
@Slf4j
@Component
public class JobRunner {

    static final int MAX_ERROR_LENGTH = 2_000;
    static final int MAX_STACK_TRACE_LENGTH = 8_000;

    private final HandlerRegistry handlerRegistry;
    private final JobQueue jobQueue;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final JobMetrics metrics;
    private final JobRateLimiter rateLimiter;

    public JobRunner(HandlerRegistry handlerRegistry, JobQueue jobQueue, RetryPolicy retryPolicy, Clock clock,
                     JobMetrics metrics, JobRateLimiter rateLimiter) {
        this.handlerRegistry = handlerRegistry;
        this.jobQueue = jobQueue;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
    }

    public void run(ClaimedJob job) {
        Instant startedAt = clock.instant();
        Optional<JobHandler> handler = handlerRegistry.find(job.type());
        if (handler.isEmpty()) {
            // Only possible if a handler was removed while jobs of its type were still queued.
            handleFailure(job, startedAt,
                    new NonRetryableJobException("No handler registered for job type '" + job.type() + "'"));
            return;
        }

        if (!rateLimiter.tryAcquire(job.type())) {
            deferForRateLimit(job);
            return;
        }

        try {
            handler.get().handle(new JobContext(job.id(), job.type(), job.payload(), job.attempt(), job.maxAttempts()));
        } catch (InterruptedException e) {
            // Worker threads are only interrupted when the pool is force-stopped during shutdown.
            // That isn't the job's fault: hand it back without using up an attempt.
            Thread.currentThread().interrupt();
            boolean released = jobQueue.release(job);
            log.warn("Job {} interrupted by shutdown; {}", job.id(),
                    released ? "returned to the queue" : "it was no longer owned by this worker");
            return;
        } catch (Exception e) {
            handleFailure(job, startedAt, e);
            return;
        }

        Instant finishedAt = clock.instant();
        Duration elapsed = Duration.between(startedAt, finishedAt);
        if (jobQueue.markSucceeded(job, startedAt, finishedAt)) {
            metrics.recordExecution(job.type(), "succeeded", elapsed);
            log.debug("Job {} type={} succeeded in {} ms", job.id(), job.type(), elapsed.toMillis());
        } else {
            metrics.recordExecution(job.type(), "lost_claim", elapsed);
            logLostClaim(job);
        }
    }

    private void handleFailure(ClaimedJob job, Instant startedAt, Exception failure) {
        RetryDecision decision = retryPolicy.decide(job.attempt(), job.maxAttempts(), failure);
        AttemptFailure details = new AttemptFailure(startedAt, clock.instant(), describe(failure), stackTraceOf(failure));
        Duration elapsed = Duration.between(details.startedAt(), details.finishedAt());
        Optional<JobStatus> next = jobQueue.recordFailure(job, details, decision);
        if (next.isEmpty()) {
            metrics.recordExecution(job.type(), "lost_claim", elapsed);
            logLostClaim(job);
            return;
        }
        metrics.recordExecution(job.type(), switch (next.get()) {
            case PENDING -> "retried";
            case DEAD -> "dead";
            default -> "failed";
        }, elapsed);
        String outcome = decision instanceof RetryDecision.Retry retry
                ? "retrying in " + retry.delay().toMillis() + " ms"
                : "moved to " + next.get();
        log.warn("Job {} type={} attempt {}/{} failed ({}): {}",
                job.id(), job.type(), job.attempt(), job.maxAttempts(), outcome, failure.toString());
    }

    /**
     * The job's type is over its rate limit: put it back for at least one refill interval, plus up to
     * a second of jitter so deferred jobs trickle back instead of all retrying at the same instant.
     * No attempt is used.
     */
    private void deferForRateLimit(ClaimedJob job) {
        Duration delay = rateLimiter.refillInterval(job.type())
                .plusMillis(ThreadLocalRandom.current().nextLong(1_000));
        if (jobQueue.release(job, delay)) {
            metrics.recordRateLimited(job.type());
            log.debug("Job {} type={} over its rate limit; deferred by {} ms", job.id(), job.type(), delay.toMillis());
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

    static String stackTraceOf(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        String trace = out.toString();
        return trace.length() <= MAX_STACK_TRACE_LENGTH ? trace : trace.substring(0, MAX_STACK_TRACE_LENGTH) + "\n\t...";
    }
}
