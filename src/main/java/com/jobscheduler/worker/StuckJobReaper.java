package com.jobscheduler.worker;

import com.jobscheduler.config.JobSchedulerProperties;
import com.jobscheduler.job.JobQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Recovers jobs whose worker died mid-run. A job claimed by a process that then crashes stays
 * RUNNING forever unless someone notices; this loop treats {@code locked_at} as a lease and
 * re-queues (or dead-letters) jobs whose lease is older than {@code jobs.reaper.lease-timeout}.
 * Safe to run on every instance at once thanks to {@code SKIP LOCKED}.
 */
@Slf4j
@Component
public class StuckJobReaper {

    private final JobQueue jobQueue;
    private final Duration leaseTimeout;
    private final int batchSize;

    public StuckJobReaper(JobQueue jobQueue, JobSchedulerProperties properties) {
        this.jobQueue = jobQueue;
        this.leaseTimeout = properties.reaper().leaseTimeout();
        this.batchSize = properties.reaper().batchSize();
    }

    @Scheduled(fixedDelayString = "${jobs.reaper.interval:30s}", initialDelayString = "${jobs.reaper.interval:30s}")
    public void reap() {
        try {
            reapOnce();
        } catch (RuntimeException e) {
            log.error("Stuck-job recovery failed; will retry on the next tick", e);
        }
    }

    /** @return number of jobs recovered */
    public int reapOnce() {
        int total = 0;
        int recovered;
        do {
            recovered = jobQueue.recoverExpiredLeases(leaseTimeout, batchSize);
            total += recovered;
        } while (recovered == batchSize);
        if (total > 0) {
            log.warn("Recovered {} job(s) whose lease expired after {}", total, leaseTimeout);
        }
        return total;
    }
}
