package com.jobscheduler.metrics;

import com.jobscheduler.job.ClaimedJob;
import com.jobscheduler.job.JobStatus;
import com.jobscheduler.job.QueueStats;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer instrumentation for the queue (visible under {@code /actuator/metrics}).
 *
 * <ul>
 *   <li>{@code jobs.queue.depth{status}}: jobs per status (refreshed periodically, not per scrape)</li>
 *   <li>{@code jobs.queue.due} / {@code jobs.queue.oldest.due.age}: backlog waiting for a worker</li>
 *   <li>{@code jobs.queue.wait{type}}: time from a job becoming due to being claimed</li>
 *   <li>{@code jobs.execution{type,outcome}}: handler run time. Its count is the number of processed
 *       jobs, so its rate over time is the throughput (jobs/sec)</li>
 *   <li>{@code jobs.rate.limited{type}}: starts deferred because the type was over its rate limit</li>
 *   <li>{@code jobs.workers.in.flight}: jobs currently dispatched to this instance's pool</li>
 * </ul>
 */
@Component
public class JobMetrics {

    private final MeterRegistry registry;
    private final Map<JobStatus, AtomicLong> depthByStatus = new EnumMap<>(JobStatus.class);
    private final AtomicLong dueNow = new AtomicLong();
    private final AtomicLong oldestDueAgeMillis = new AtomicLong();

    public JobMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (JobStatus status : JobStatus.values()) {
            AtomicLong value = new AtomicLong();
            depthByStatus.put(status, value);
            Gauge.builder("jobs.queue.depth", value, AtomicLong::get)
                    .description("Number of jobs in each status")
                    .tag("status", status.name())
                    .register(registry);
        }
        Gauge.builder("jobs.queue.due", dueNow, AtomicLong::get)
                .description("PENDING jobs that are due and waiting for a free worker")
                .register(registry);
        Gauge.builder("jobs.queue.oldest.due.age", oldestDueAgeMillis, millis -> millis.get() / 1000.0)
                .description("How long the oldest due job has been waiting")
                .baseUnit("seconds")
                .register(registry);
    }

    public void updateQueue(QueueStats stats) {
        stats.countsByStatus().forEach((status, count) -> depthByStatus.get(status).set(count));
        dueNow.set(stats.dueNow());
        oldestDueAgeMillis.set(stats.oldestDueAgeSeconds() == null ? 0 : (long) (stats.oldestDueAgeSeconds() * 1000));
    }

    /** Pickup latency: how long a due job waited before a worker claimed it. */
    public void recordClaimed(ClaimedJob job) {
        Duration waited = Duration.between(job.runAt(), job.claimedAt());
        Timer.builder("jobs.queue.wait")
                .description("Time from a job becoming due to being claimed")
                .tag("type", job.type())
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(waited.isNegative() ? Duration.ZERO : waited);
    }

    public void recordExecution(String type, String outcome, Duration duration) {
        Timer.builder("jobs.execution")
                .description("Handler execution time; count = jobs processed")
                .tag("type", type)
                .tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(duration);
    }

    public void recordRateLimited(String type) {
        Counter.builder("jobs.rate.limited")
                .description("Job starts deferred because the type was over its rate limit")
                .tag("type", type)
                .register(registry)
                .increment();
    }
}
