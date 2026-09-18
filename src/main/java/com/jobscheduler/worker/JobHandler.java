package com.jobscheduler.worker;

/**
 * Strategy for executing one job type. Implementations are Spring beans and are discovered by
 * {@link HandlerRegistry}; adding a job type means adding a handler, not editing the poller.
 *
 * <p>Returning normally marks the job as succeeded. Throwing marks the attempt as failed.
 * Handlers may run more than once for the same job (retries, or re-execution after a worker
 * crash), so they should be idempotent.
 */
public interface JobHandler {

    /** The job type this handler executes; must be unique and match {@link com.jobscheduler.job.JobType#PATTERN}. */
    String type();

    void handle(JobContext context) throws Exception;
}
