package com.jobscheduler.worker;

import com.jobscheduler.config.JobSchedulerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Shuts the worker side down without losing jobs (SIGTERM, Ctrl+C, a rolling deploy):
 * <ol>
 *   <li>stop claiming: the poller finishes any poll in progress and claims nothing more;</li>
 *   <li>drain: running and already-queued jobs complete, up to {@code jobs.worker.drain-timeout};</li>
 *   <li>anything still unfinished is interrupted and put back in the queue for another instance.</li>
 * </ol>
 * Runs on {@link ContextClosedEvent}, before beans (including the DataSource) are destroyed, so
 * jobs can still record their results while draining.
 */
@Slf4j
@Component
public class GracefulShutdown {

    private final JobPoller jobPoller;
    private final WorkerPool workerPool;
    private final Duration drainTimeout;

    public GracefulShutdown(JobPoller jobPoller, WorkerPool workerPool, JobSchedulerProperties properties) {
        this.jobPoller = jobPoller;
        this.workerPool = workerPool;
        this.drainTimeout = properties.worker().drainTimeout();
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        long start = System.nanoTime();
        jobPoller.stopPolling();
        int inFlight = workerPool.inFlight();
        log.info("Draining {} in-flight job(s) (timeout {})", inFlight, drainTimeout);
        int handedBack = workerPool.drain(drainTimeout);
        log.info("Worker pool drained in {} ms; {} job(s) handed back to the queue",
                (System.nanoTime() - start) / 1_000_000, handedBack);
    }
}
