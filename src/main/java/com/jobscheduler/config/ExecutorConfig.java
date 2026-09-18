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
 *       marked RUNNING in the database, and are lost if the process dies. The queue is only a
 *       small prefetch buffer so threads don't idle between polls.</li>
 *   <li><b>AbortPolicy.</b> The poller only claims as many jobs as the pool has room for, so a
 *       rejection should not happen. If one does, the caller gets an exception and hands the job
 *       back to the queue. CallerRunsPolicy would instead run the job on the scheduler thread and
 *       stall polling; DiscardPolicy would silently strand a claimed job.</li>
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
        executor.setQueueCapacity(worker.queueCapacity());
        executor.setThreadNamePrefix("job-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
