package com.jobscheduler.worker;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jobscheduler.config.ExecutorConfig;
import com.jobscheduler.config.JobSchedulerProperties;
import com.jobscheduler.job.ClaimedJob;
import com.jobscheduler.job.JobQueue;
import com.jobscheduler.job.JobService;
import com.jobscheduler.job.SubmissionResult;
import com.jobscheduler.job.dto.SubmitJobRequest;
import com.jobscheduler.metrics.JobMetrics;
import com.jobscheduler.support.AbstractIntegrationTest;
import com.jobscheduler.support.TestHandlers.CountingJobHandler;
import com.jobscheduler.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The headline tests: many workers racing over the same jobs table must never run a job twice
 * and never lose one. Contention is made as high as possible: all threads are released at the
 * same instant by a barrier and claim with small, random batch sizes.
 */
class ConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private JobQueue jobQueue;
    @Autowired
    private JobRunner jobRunner;
    @Autowired
    private JobMetrics jobMetrics;
    @Autowired
    private JobService jobService;

    /** Bulk-inserts {@code count} due jobs of the counting type; every 10th fails its first attempt. */
    private List<UUID> insertJobs(int count, boolean withPlannedFailures) {
        List<UUID> ids = new ArrayList<>(count);
        List<Object[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            boolean fail = withPlannedFailures && i % 10 == 0;
            rows.add(new Object[]{id, "{\"failOnFirstAttempt\": " + fail + "}", i % 5});
        }
        jdbc.batchUpdate("""
                INSERT INTO jobs (id, type, payload, status, priority, attempts, max_attempts, run_at, created_at, updated_at)
                VALUES (?, 'counting', CAST(? AS jsonb), 'PENDING', ?, 0, 3, now(), now(), now())
                """, rows);
        return ids;
    }

    @Test
    void concurrentClaimersNeverReceiveTheSameJob() throws Exception {
        int jobs = 2_000;
        int claimers = 16;
        Set<UUID> inserted = new HashSet<>(insertJobs(jobs, false));

        Queue<UUID> claimed = new ConcurrentLinkedQueue<>();
        CyclicBarrier startTogether = new CyclicBarrier(claimers);
        ExecutorService threads = Executors.newFixedThreadPool(claimers);
        List<Future<?>> results = new ArrayList<>();
        for (int i = 0; i < claimers; i++) {
            String workerId = "claimer-" + i;
            results.add(threads.submit(() -> {
                startTogether.await();
                List<ClaimedJob> batch;
                do {
                    batch = jobQueue.claim(ThreadLocalRandom.current().nextInt(1, 26), workerId);
                    batch.forEach(job -> claimed.add(job.id()));
                } while (!batch.isEmpty());
                return null;
            }));
        }
        for (Future<?> result : results) {
            result.get(60, TimeUnit.SECONDS);
        }
        threads.shutdown();

        assertThat(claimed).hasSize(jobs);                               // nothing lost...
        assertThat(new HashSet<>(claimed)).isEqualTo(inserted);          // ...and nothing claimed twice
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = 'RUNNING' AND attempts = 1", Long.class))
                .isEqualTo(jobs);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT locked_by) FROM jobs", Long.class))
                .as("work should be spread across claimers").isGreaterThan(1);
    }

    @Test
    void everyJobRunsExactlyOnceAcrossIndependentWorkerInstances() throws Exception {
        int jobs = 1_000;
        int instances = 4;
        List<UUID> ids = insertJobs(jobs, true); // 100 of them fail once and are retried

        // Each "instance" has its own identity, thread pool, WorkerPool and JobPoller, exactly as
        // separate application processes would; they share only the database.
        JobSchedulerProperties props = TestProperties.with(Map.of(
                "jobs.worker.pool-size", "4", "jobs.worker.queue-capacity", "4", "jobs.poller.batch-size", "5"));
        List<ThreadPoolTaskExecutor> executors = new ArrayList<>();
        List<JobPoller> pollers = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            ThreadPoolTaskExecutor executor = new ExecutorConfig().jobWorkerExecutor(props);
            executor.initialize();
            executors.add(executor);
            WorkerPool pool = new WorkerPool(executor, jobRunner, jobQueue, props, new SimpleMeterRegistry());
            pollers.add(new JobPoller(jobQueue, pool, new WorkerIdentity("instance-" + i), jobMetrics, props));
        }

        AtomicBoolean running = new AtomicBoolean(true);
        CyclicBarrier startTogether = new CyclicBarrier(instances);
        ExecutorService pollLoops = Executors.newFixedThreadPool(instances);
        for (JobPoller poller : pollers) {
            pollLoops.submit(() -> {
                startTogether.await();
                while (running.get()) {
                    poller.pollOnce();
                    Thread.sleep(2);
                }
                return null;
            });
        }

        try {
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(200)).until(() ->
                    jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = 'SUCCEEDED'", Long.class) == jobs);
        } finally {
            running.set(false);
            pollLoops.shutdown();
            pollLoops.awaitTermination(10, TimeUnit.SECONDS);
            executors.forEach(ThreadPoolTaskExecutor::shutdown);
        }

        // Exactly once per attempt: 1000 first attempts + 100 retries, none executed twice.
        assertThat(countingHandler.allExecutions().values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
        assertThat(countingHandler.totalExecutions()).isEqualTo(jobs + jobs / 10);
        for (int i = 0; i < jobs; i++) {
            UUID id = ids.get(i);
            boolean plannedFailure = i % 10 == 0;
            assertThat(countingHandler.executions(id, 1)).isEqualTo(1);
            assertThat(countingHandler.executions(id, 2)).isEqualTo(plannedFailure ? 1 : 0);
        }

        // The database agrees: one history row per attempt, and every job finished.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_attempts", Long.class)).isEqualTo(jobs + jobs / 10L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT job_id, attempt FROM job_attempts GROUP BY 1, 2 HAVING count(*) > 1) d",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT worker_id) FROM job_attempts", Long.class))
                .as("all instances should have done work").isEqualTo(instances);
    }

    @Test
    void concurrentSubmissionsWithOneIdempotencyKeyCreateOneJob() throws Exception {
        int clients = 32;
        CyclicBarrier startTogether = new CyclicBarrier(clients);
        ExecutorService threads = Executors.newFixedThreadPool(clients);
        List<Future<SubmissionResult>> results = new ArrayList<>();
        SubmitJobRequest request = new SubmitJobRequest(CountingJobHandler.TYPE,
                JsonNodeFactory.instance.objectNode().put("order", 42), null, null, null);
        for (int i = 0; i < clients; i++) {
            results.add(threads.submit(() -> {
                startTogether.await();
                return jobService.submit(request, "order-42");
            }));
        }
        List<SubmissionResult> outcomes = new ArrayList<>();
        for (Future<SubmissionResult> result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        threads.shutdown();

        assertThat(outcomes).filteredOn(SubmissionResult::created).hasSize(1);
        assertThat(outcomes).extracting(outcome -> outcome.job().getId()).containsOnly(outcomes.get(0).job().getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE idempotency_key = 'order-42'", Long.class))
                .isEqualTo(1);
    }
}
