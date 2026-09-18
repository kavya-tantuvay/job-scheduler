package com.jobscheduler.worker;

import com.jobscheduler.config.JobSchedulerProperties;
import com.jobscheduler.job.ClaimedJob;
import com.jobscheduler.job.JobQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodically claims due jobs and hands them to the worker pool. Several instances of the
 * application can poll the same table at once: {@code SKIP LOCKED} gives each a disjoint batch.
 */
@Slf4j
@Component
public class JobPoller {

    private final JobQueue jobQueue;
    private final WorkerPool workerPool;
    private final WorkerIdentity workerIdentity;
    private final int batchSize;

    public JobPoller(JobQueue jobQueue, WorkerPool workerPool, WorkerIdentity workerIdentity,
                     JobSchedulerProperties properties) {
        this.jobQueue = jobQueue;
        this.workerPool = workerPool;
        this.workerIdentity = workerIdentity;
        this.batchSize = properties.poller().batchSize();
    }

    /** fixedDelay (not fixedRate): the next poll starts only after this one has finished. */
    @Scheduled(fixedDelayString = "${jobs.poller.interval:1s}")
    public void poll() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.error("Job poll failed; will retry on the next tick", e);
        }
    }

    /**
     * Claims and dispatches jobs until the pool is full or no more jobs are due.
     *
     * @return number of jobs dispatched
     */
    public int pollOnce() {
        int dispatched = 0;
        while (true) {
            int limit = Math.min(batchSize, workerPool.availableCapacity());
            if (limit == 0) {
                break;
            }
            List<ClaimedJob> claimed = jobQueue.claim(limit, workerIdentity.id());
            claimed.forEach(workerPool::dispatch);
            dispatched += claimed.size();
            if (claimed.size() < limit) {
                break; // nothing more is due right now
            }
        }
        if (dispatched > 0) {
            log.debug("Dispatched {} job(s)", dispatched);
        }
        return dispatched;
    }
}
