package com.jobscheduler.worker;

import com.jobscheduler.config.ExecutorConfig;
import com.jobscheduler.config.JobSchedulerProperties;
import com.jobscheduler.job.ClaimedJob;
import com.jobscheduler.job.JobQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * Runs claimed jobs on the bounded worker executor. The executor is used directly (rather than
 * through {@code @Async}) so the poller can ask how much room is left and react to rejection.
 *
 * <p>Room is tracked with a semaphore holding one permit per slot (threads + queue). A permit is
 * taken before a job is handed to the executor and returned when the job finishes, so
 * {@link #availableCapacity()} is exact. Reading the executor's own active/queued counts instead
 * would be only an estimate (a task being handed from the queue to a thread is briefly counted
 * in neither) and leads to over-claiming.
 */
@Slf4j
@Component
public class WorkerPool {

    private final ThreadPoolTaskExecutor executor;
    private final JobRunner jobRunner;
    private final JobQueue jobQueue;
    private final int capacity;
    private final Semaphore slots;

    public WorkerPool(@Qualifier(ExecutorConfig.WORKER_EXECUTOR) ThreadPoolTaskExecutor executor,
                      JobRunner jobRunner, JobQueue jobQueue, JobSchedulerProperties properties) {
        this.executor = executor;
        this.jobRunner = jobRunner;
        this.jobQueue = jobQueue;
        this.capacity = properties.worker().poolSize() + properties.worker().queueCapacity();
        this.slots = new Semaphore(capacity);
    }

    /**
     * How many more jobs the pool can accept right now. The poller never claims more than this,
     * so claimed jobs don't pile up in memory while marked RUNNING in the database.
     */
    public int availableCapacity() {
        return slots.availablePermits();
    }

    /** Number of jobs dispatched to this pool that have not finished yet (running or queued). */
    public int inFlight() {
        return capacity - slots.availablePermits();
    }

    public void dispatch(ClaimedJob job) {
        if (!slots.tryAcquire()) {
            // Only reachable if something other than the poller dispatches concurrently.
            giveBack(job, "no free worker slot");
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    runSafely(job);
                } finally {
                    slots.release();
                }
            });
        } catch (TaskRejectedException e) {
            // The executor is shutting down.
            slots.release();
            giveBack(job, "worker pool is not accepting tasks");
        }
    }

    /** The job is already RUNNING in the database; return it to the queue instead of stranding it. */
    private void giveBack(ClaimedJob job, String reason) {
        boolean released = jobQueue.release(job);
        log.warn("Could not run job {} ({}); {}", job.id(), reason,
                released ? "returned it to the queue" : "it was no longer owned by this worker");
    }

    private void runSafely(ClaimedJob job) {
        try {
            jobRunner.run(job);
        } catch (RuntimeException e) {
            // e.g. the database was unreachable when recording the outcome. The job stays RUNNING
            // and is picked up again once its lease expires.
            log.error("Unexpected error while running job {}", job.id(), e);
        }
    }
}
