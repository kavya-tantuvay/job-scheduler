package com.jobscheduler.worker;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobscheduler.job.AttemptOutcome;
import com.jobscheduler.job.ClaimedJob;
import com.jobscheduler.job.Job;
import com.jobscheduler.job.JobAttempt;
import com.jobscheduler.job.JobQueue;
import com.jobscheduler.job.JobService;
import com.jobscheduler.job.JobStatus;
import com.jobscheduler.job.dto.SubmitJobRequest;
import com.jobscheduler.support.AbstractIntegrationTest;
import com.jobscheduler.support.TestHandlers.CountingJobHandler;
import com.jobscheduler.worker.handlers.FlakyJobHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Full job lifecycle against PostgreSQL: submit → poll → execute → succeed / retry / dead-letter / recover. */
class JobLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    private JobService jobService;
    @Autowired
    private JobPoller jobPoller;
    @Autowired
    private JobQueue jobQueue;
    @Autowired
    private StuckJobReaper reaper;

    private UUID submit(String type, ObjectNode payload, Integer maxAttempts) {
        return jobService.submit(new SubmitJobRequest(type, payload, null, maxAttempts, null), null).job().getId();
    }

    /** Keeps polling (as the scheduled poller would) until the job reaches {@code status}. */
    private Job runUntil(UUID id, JobStatus status) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(25)).until(() -> {
            jobPoller.pollOnce();
            return jobService.get(id).getStatus() == status;
        });
        return jobService.get(id);
    }

    private static ObjectNode payload() {
        return JsonNodeFactory.instance.objectNode();
    }

    @Test
    void submittedJobIsClaimedExecutedAndSucceeds() {
        UUID id = submit(CountingJobHandler.TYPE, payload(), null);

        Job job = runUntil(id, JobStatus.SUCCEEDED);

        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getLockedBy()).isNotBlank();
        assertThat(countingHandler.executions(id, 1)).isEqualTo(1);
        List<JobAttempt> history = jobService.attempts(id);
        assertThat(history).singleElement().satisfies(attempt -> {
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.SUCCEEDED);
            assertThat(attempt.getWorkerId()).isEqualTo(job.getLockedBy());
        });
    }

    @Test
    void higherPriorityJobsAreClaimedFirst() {
        UUID low = jobService.submit(new SubmitJobRequest(CountingJobHandler.TYPE, payload(), 1, null, null), null).job().getId();
        UUID high = jobService.submit(new SubmitJobRequest(CountingJobHandler.TYPE, payload(), 90, null, null), null).job().getId();

        List<ClaimedJob> claimed = jobQueue.claim(1, "worker-x");

        assertThat(claimed).extracting(ClaimedJob::id).containsExactly(high);
        assertThat(jobService.get(low).getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void jobScheduledInTheFutureIsNotClaimedEarly() {
        UUID id = jobService.submit(new SubmitJobRequest(CountingJobHandler.TYPE, payload(), null, null,
                Instant.now().plusSeconds(3600)), null).job().getId();

        assertThat(jobPoller.pollOnce()).isZero();
        assertThat(jobService.get(id).getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void transientFailuresAreRetriedWithBackoffUntilSuccess() {
        UUID id = submit(FlakyJobHandler.TYPE, payload().put("failUntilAttempt", 3), 5);

        Job job = runUntil(id, JobStatus.SUCCEEDED);

        assertThat(job.getAttempts()).isEqualTo(3);
        assertThat(jobService.attempts(id)).extracting(JobAttempt::getOutcome)
                .containsExactly(AttemptOutcome.FAILED, AttemptOutcome.FAILED, AttemptOutcome.SUCCEEDED);
        assertThat(jobService.attempts(id).get(0).getStackTrace()).contains("FlakyJobHandler");
    }

    @Test
    void exhaustedJobIsDeadLetteredAndCanBeRedriven() {
        UUID id = submit(FlakyJobHandler.TYPE, payload().put("failUntilAttempt", 99), 2);

        Job dead = runUntil(id, JobStatus.DEAD);
        assertThat(dead.getAttempts()).isEqualTo(2);
        assertThat(dead.getLastError()).contains("Simulated transient failure on attempt 2");
        assertThat(jobService.attempts(id)).hasSize(2).allMatch(a -> a.getOutcome() == AttemptOutcome.FAILED);

        Job requeued = jobService.retry(id, 3);
        assertThat(requeued.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(requeued.getMaxAttempts()).isEqualTo(5);

        Job deadAgain = runUntil(id, JobStatus.DEAD);
        assertThat(deadAgain.getAttempts()).isEqualTo(5);
        assertThat(jobService.attempts(id)).extracting(JobAttempt::getAttempt).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void nonRetryableFailureFailsWithoutRetrying() {
        UUID id = submit(FlakyJobHandler.TYPE, payload().put("permanent", true), 5);

        Job job = runUntil(id, JobStatus.FAILED);

        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getLastError()).contains("NonRetryableJobException");
    }

    @Test
    void jobOfACrashedWorkerIsRecoveredAndItsLateResultIsRejected() {
        UUID id = submit(CountingJobHandler.TYPE, payload(), 3);
        ClaimedJob crashedClaim = jobQueue.claim(1, "crashed-worker").get(0);
        // Simulate the worker dying: its lease is far in the past and it never reports back.
        jdbc.update("UPDATE jobs SET locked_at = now() - INTERVAL '1 hour' WHERE id = ?", id);

        assertThat(reaper.reapOnce()).isEqualTo(1);

        Job recovered = jobService.get(id);
        assertThat(recovered.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(recovered.getLastError()).contains("Lease expired", "crashed-worker");
        assertThat(jobService.attempts(id)).singleElement().satisfies(attempt -> {
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.LEASE_EXPIRED);
            assertThat(attempt.getWorkerId()).isEqualTo("crashed-worker");
        });

        // The "dead" worker comes back and tries to report success: the fence rejects it.
        assertThat(jobQueue.markSucceeded(crashedClaim, Instant.now(), Instant.now())).isFalse();

        Job finished = runUntil(id, JobStatus.SUCCEEDED);
        assertThat(finished.getAttempts()).isEqualTo(2);
        assertThat(jobService.attempts(id)).extracting(JobAttempt::getOutcome)
                .containsExactly(AttemptOutcome.LEASE_EXPIRED, AttemptOutcome.SUCCEEDED);
    }

    @Test
    void jobWhoseLeaseExpiresOnItsLastAttemptIsDeadLettered() {
        UUID id = submit(CountingJobHandler.TYPE, payload(), 1);
        jobQueue.claim(1, "crashed-worker");
        jdbc.update("UPDATE jobs SET locked_at = now() - INTERVAL '1 hour' WHERE id = ?", id);

        reaper.reapOnce();

        assertThat(jobService.get(id).getStatus()).isEqualTo(JobStatus.DEAD);
    }

    @Test
    void reaperLeavesJobsWithinTheirLeaseAlone() {
        submit(CountingJobHandler.TYPE, payload(), 3);
        jobQueue.claim(1, "healthy-worker");

        assertThat(reaper.reapOnce()).isZero();
    }

    @Test
    void cancelledJobIsNeverExecuted() {
        UUID id = submit(CountingJobHandler.TYPE, payload(), null);

        jobService.cancel(id);
        jobPoller.pollOnce();

        assertThat(jobService.get(id).getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(countingHandler.totalExecutions()).isZero();
    }

    @Test
    void releasedClaimGetsItsAttemptBack() {
        UUID id = submit(CountingJobHandler.TYPE, payload(), 3);
        ClaimedJob claim = jobQueue.claim(1, "worker-x").get(0);

        assertThat(jobQueue.release(claim, Duration.ofSeconds(30))).isTrue();

        Job job = jobService.get(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getRunAt()).isAfter(Instant.now().plusSeconds(20));
    }
}
