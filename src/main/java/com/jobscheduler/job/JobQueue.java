package com.jobscheduler.job;

import com.jobscheduler.retry.RetryDecision;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The worker-side view of the {@code jobs} table as a queue: claim due jobs and record the outcome
 * of each claim. Each method is its own short transaction; no transaction is held while a job's
 * handler is running.
 *
 * <p>Outcome updates are fenced on the claim ({@code locked_by} + {@code attempts}). The attempt
 * history row is written in the same transaction and only if the fenced update matched, so a
 * worker that lost its claim leaves no trace and each attempt is recorded exactly once.
 */
@Component
public class JobQueue {

    private static final Comparator<ClaimedJob> DISPATCH_ORDER =
            Comparator.comparingInt(ClaimedJob::priority).reversed().thenComparing(ClaimedJob::runAt);

    private final JobRepository jobRepository;
    private final JobAttemptRepository attemptRepository;

    public JobQueue(JobRepository jobRepository, JobAttemptRepository attemptRepository) {
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
    }

    /**
     * Claims up to {@code limit} due jobs for {@code workerId}. The transaction boundary matters:
     * the row locks taken by {@code FOR UPDATE SKIP LOCKED} last until commit, and the rows are
     * marked RUNNING before that commit, so there is no window in which another worker could see
     * a claimed row as both unlocked and PENDING.
     *
     * @return claimed jobs, highest priority first
     */
    @Transactional
    public List<ClaimedJob> claim(int limit, String workerId) {
        if (limit <= 0) {
            return List.of();
        }
        return jobRepository.claimDueJobs(limit, workerId).stream()
                .map(ClaimedJob::from)
                // UPDATE ... RETURNING doesn't preserve the CTE's ORDER BY.
                .sorted(DISPATCH_ORDER)
                .toList();
    }

    /** @return false if the claim was lost (job reclaimed by someone else); the result is discarded */
    @Transactional
    public boolean markSucceeded(ClaimedJob job, Instant startedAt, Instant finishedAt) {
        if (jobRepository.markSucceeded(job.id(), job.workerId(), job.attempt()) == 0) {
            return false;
        }
        attemptRepository.save(JobAttempt.succeeded(job, startedAt, finishedAt));
        return true;
    }

    /**
     * Applies the retry policy's decision to a failed attempt.
     *
     * @return the job's new status, or empty if the claim was lost and nothing was changed
     */
    @Transactional
    public Optional<JobStatus> recordFailure(ClaimedJob job, AttemptFailure failure, RetryDecision decision) {
        int updated;
        JobStatus next;
        if (decision instanceof RetryDecision.Retry retry) {
            updated = jobRepository.scheduleRetry(job.id(), job.workerId(), job.attempt(), failure.error(),
                    retry.delay().toMillis());
            next = JobStatus.PENDING;
        } else if (decision instanceof RetryDecision.DeadLetter) {
            updated = jobRepository.markDead(job.id(), job.workerId(), job.attempt(), failure.error());
            next = JobStatus.DEAD;
        } else {
            updated = jobRepository.markFailed(job.id(), job.workerId(), job.attempt(), failure.error());
            next = JobStatus.FAILED;
        }
        if (updated == 0) {
            return Optional.empty();
        }
        attemptRepository.save(JobAttempt.failed(job, failure));
        return Optional.of(next);
    }

    /** @return number of RUNNING jobs with an expired lease that were re-queued or dead-lettered */
    @Transactional
    public int recoverExpiredLeases(Duration leaseTimeout, int limit) {
        return jobRepository.recoverExpiredLeases(leaseTimeout.toMillis(), leaseTimeout.toString(), limit);
    }

    /** Puts a claimed-but-never-started job back in the queue. */
    @Transactional
    public boolean release(ClaimedJob job) {
        return jobRepository.releaseClaim(job.id(), job.workerId(), job.attempt()) == 1;
    }
}
