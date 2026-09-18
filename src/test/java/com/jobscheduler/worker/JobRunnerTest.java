package com.jobscheduler.worker;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jobscheduler.job.AttemptFailure;
import com.jobscheduler.job.ClaimedJob;
import com.jobscheduler.job.JobQueue;
import com.jobscheduler.job.JobStatus;
import com.jobscheduler.metrics.JobMetrics;
import com.jobscheduler.ratelimit.JobRateLimiter;
import com.jobscheduler.retry.RetryDecision;
import com.jobscheduler.retry.RetryPolicy;
import com.jobscheduler.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobRunnerTest {

    @Mock
    private JobQueue jobQueue;
    @Mock
    private JobRateLimiter rateLimiter;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private JobHandler handler;
    private JobRunner runner;

    @BeforeEach
    void setUp() {
        handler = mock(JobHandler.class);
        when(handler.type()).thenReturn("test");
        runner = new JobRunner(new HandlerRegistry(List.of(handler)), jobQueue,
                new RetryPolicy(TestProperties.defaults()), Clock.systemUTC(), new JobMetrics(meterRegistry), rateLimiter);
    }

    private static ClaimedJob claimed(String type, int attempt, int maxAttempts) {
        Instant now = Instant.now();
        return new ClaimedJob(UUID.randomUUID(), type, JsonNodeFactory.instance.objectNode(), 0, attempt, maxAttempts,
                now, now, "worker-1");
    }

    @Test
    void successfulHandlerMarksJobSucceeded() throws Exception {
        ClaimedJob job = claimed("test", 1, 3);
        when(rateLimiter.tryAcquire("test")).thenReturn(true);
        when(jobQueue.markSucceeded(eq(job), any(), any())).thenReturn(true);

        runner.run(job);

        ArgumentCaptor<JobContext> context = ArgumentCaptor.forClass(JobContext.class);
        verify(handler).handle(context.capture());
        assertThat(context.getValue().jobId()).isEqualTo(job.id());
        assertThat(context.getValue().attempt()).isEqualTo(1);
        verify(jobQueue).markSucceeded(eq(job), any(), any());
        assertThat(meterRegistry.get("jobs.execution").tag("outcome", "succeeded").timer().count()).isEqualTo(1);
    }

    @Test
    void transientFailureIsScheduledForRetryWithErrorDetails() throws Exception {
        ClaimedJob job = claimed("test", 1, 3);
        when(rateLimiter.tryAcquire("test")).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("downstream 503")).when(handler).handle(any());
        when(jobQueue.recordFailure(eq(job), any(), any())).thenReturn(Optional.of(JobStatus.PENDING));

        runner.run(job);

        ArgumentCaptor<AttemptFailure> failure = ArgumentCaptor.forClass(AttemptFailure.class);
        ArgumentCaptor<RetryDecision> decision = ArgumentCaptor.forClass(RetryDecision.class);
        verify(jobQueue).recordFailure(eq(job), failure.capture(), decision.capture());
        assertThat(decision.getValue()).isInstanceOf(RetryDecision.Retry.class);
        assertThat(failure.getValue().error()).isEqualTo("java.lang.IllegalStateException: downstream 503");
        assertThat(failure.getValue().stackTrace()).contains("JobRunnerTest");
        verify(jobQueue, never()).markSucceeded(any(), any(), any());
    }

    @Test
    void failureOnLastAttemptIsDeadLettered() throws Exception {
        ClaimedJob job = claimed("test", 3, 3);
        when(rateLimiter.tryAcquire("test")).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("still broken")).when(handler).handle(any());
        when(jobQueue.recordFailure(eq(job), any(), any())).thenReturn(Optional.of(JobStatus.DEAD));

        runner.run(job);

        verify(jobQueue).recordFailure(eq(job), any(), any(RetryDecision.DeadLetter.class));
    }

    @Test
    void unknownTypeFailsPermanently() {
        ClaimedJob job = claimed("removed-type", 1, 3);
        when(jobQueue.recordFailure(eq(job), any(), any())).thenReturn(Optional.of(JobStatus.FAILED));

        runner.run(job);

        verify(jobQueue).recordFailure(eq(job), any(), any(RetryDecision.Fail.class));
    }

    @Test
    void rateLimitedJobIsDeferredWithoutRunningTheHandler() throws Exception {
        ClaimedJob job = claimed("test", 1, 3);
        when(rateLimiter.tryAcquire("test")).thenReturn(false);
        when(rateLimiter.refillInterval("test")).thenReturn(Duration.ofMillis(200));
        when(jobQueue.release(eq(job), any())).thenReturn(true);

        runner.run(job);

        verify(handler, never()).handle(any());
        ArgumentCaptor<Duration> delay = ArgumentCaptor.forClass(Duration.class);
        verify(jobQueue).release(eq(job), delay.capture());
        assertThat(delay.getValue()).isBetween(Duration.ofMillis(200), Duration.ofMillis(1200));
    }

    @Test
    void interruptionDuringShutdownHandsTheJobBackWithoutUsingAnAttempt() throws Exception {
        ClaimedJob job = claimed("test", 1, 3);
        when(rateLimiter.tryAcquire("test")).thenReturn(true);
        org.mockito.Mockito.doThrow(new InterruptedException()).when(handler).handle(any());
        when(jobQueue.release(job)).thenReturn(true);

        runner.run(job);

        verify(jobQueue).release(job);
        verify(jobQueue, never()).recordFailure(any(), any(), any());
        assertThat(Thread.interrupted()).isTrue(); // interrupt flag restored (and cleared here)
    }

    @Test
    void describeIncludesRootCause() {
        var error = new IllegalStateException("outer", new java.io.IOException("connection reset"));
        assertThat(JobRunner.describe(error))
                .isEqualTo("java.lang.IllegalStateException: outer (root cause: java.io.IOException: connection reset)");
    }
}
