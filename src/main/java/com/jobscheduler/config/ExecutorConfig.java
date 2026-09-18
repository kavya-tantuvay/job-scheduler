package com.jobscheduler.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * The worker pool that executes job handlers.
 *
 * <ul>
 *   <li><b>Fixed size (core = max).</b> A ThreadPoolExecutor only grows past its core size when
 *       the queue is full, so core &lt; max with a queue gives confusing behaviour. One number,
 *       {@code pool-size}, states how many jobs run concurrently on this instance.</li>
 *   <li><b>Bounded queue.</b> An unbounded queue hides overload: jobs pile up in memory, already
 *       marked RUNNING in the database, and are lost if the process dies. {@code queue-capacity}
 *       is only a small prefetch buffer so threads don't idle between polls.</li>
 *   <li><b>Admission is bounded by {@code WorkerPool}'s semaphore</b> of
 *       {@code pool-size + queue-capacity} permits. A worker returns its permit a moment before its
 *       thread is free to take the next task, so the executor's internal queue is sized to the
 *       full permit count: it can then never overflow while the semaphore is respected.</li>
 *   <li><b>AbortPolicy.</b> With the above, rejection only happens when the pool is shutting down.
 *       The caller gets an exception and hands the claimed job back to the queue.
 *       CallerRunsPolicy would instead run the job on the scheduler thread and stall polling;
 *       DiscardPolicy would silently strand a job that is marked RUNNING.</li>
 * </ul>
 */
@Configuration
public class ExecutorConfig {

    public static final String WORKER_EXECUTOR = "jobWorkerExecutor";

    @Bean(name = WORKER_EXECUTOR)
    public ThreadPoolTaskExecutor jobWorkerExecutor(JobSchedulerProperties properties) {
        JobSchedulerProperties.Worker worker = properties.worker();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(worker.poolSize());
        executor.setMaxPoolSize(worker.poolSize());
        executor.setQueueCapacity(worker.poolSize() + worker.queueCapacity());
        executor.setThreadNamePrefix("job-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // GracefulShutdown drains the pool explicitly; these only make Spring's own shutdown wait
        // instead of interrupting jobs if it gets there first.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(worker.drainTimeout().toMillis());
        return executor;
    }
}
