package com.jobscheduler.job;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * The worker-side view of the {@code jobs} table as a queue: claim due jobs and record the outcome
 * of each claim. Each method is its own short transaction; no transaction is held while a job's
 * handler is running.
 */
@Component
public class JobQueue {

    private static final Comparator<ClaimedJob> DISPATCH_ORDER =
            Comparator.comparingInt(ClaimedJob::priority).reversed().thenComparing(ClaimedJob::runAt);

    private final JobRepository jobRepository;

    public JobQueue(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
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
}
