package com.jobscheduler.benchmark;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jobscheduler.config.ExecutorConfig;
import com.jobscheduler.config.JobSchedulerProperties;
import com.jobscheduler.job.JobQueue;
import com.jobscheduler.job.JobService;
import com.jobscheduler.job.dto.SubmitJobRequest;
import com.jobscheduler.metrics.JobMetrics;
import com.jobscheduler.support.AbstractIntegrationTest;
import com.jobscheduler.support.TestProperties;
import com.jobscheduler.worker.JobContext;
import com.jobscheduler.worker.JobHandler;
import com.jobscheduler.worker.JobPoller;
import com.jobscheduler.worker.JobRunner;
import com.jobscheduler.worker.WorkerIdentity;
import com.jobscheduler.worker.WorkerPool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Measures real throughput and latency of the queue on the current machine. Not part of the
 * normal build; run with {@code ./mvnw verify -Pbenchmark} (sizes via {@code -Dbench.jobs=...}).
 *
 * <p>Every reported number is computed from timestamps recorded during the run
 * ({@code jobs.created_at}, {@code job_attempts.started_at/finished_at}); nothing is estimated.
 * Worker "instances" are simulated inside one JVM, each with its own thread pool, poller and
 * identity, sharing one database, exactly like separate processes would except for the JVM.
 * Results are printed and written to {@code target/benchmark/results.md}.
 */
@Import(QueueBenchmark.BenchmarkHandlers.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=40",
        "logging.level.com.jobscheduler.job.JobService=WARN"
})
class QueueBenchmark extends AbstractIntegrationTest {

    private static final int JOBS = Integer.getInteger("bench.jobs", 10_000);
    private static final int IO_JOBS = Integer.getInteger("bench.ioJobs", 2_000);
    private static final int IO_WORK_MS = Integer.getInteger("bench.ioWorkMs", 20);
    private static final int LATENCY_RATE = Integer.getInteger("bench.latencyRate", 200);
    private static final int LATENCY_SECONDS = Integer.getInteger("bench.latencySeconds", 10);

    @Autowired
    private JobQueue jobQueue;
    @Autowired
    private JobRunner jobRunner;
    @Autowired
    private JobMetrics jobMetrics;
    @Autowired
    private JobService jobService;

    private final List<String> report = new ArrayList<>();

    @Test
    void measureThroughputAndLatency() throws Exception {
        header();
        warmUp();

        report.add("");
        report.add("## Database baseline (plain single-row INSERT + COMMIT, no queue code)");
        report.add("");
        report.add("Every job needs at least one durable commit (claim, then result + history row), so the");
        report.add("database's commit rate is the ceiling the queue numbers below should be read against.");
        report.add("");
        commitBaseline(1);
        commitBaseline(8);

        report.add("");
        report.add("## Enqueue (REST service layer, 8 client threads)");
        enqueueThroughput(5_000, 8);

        report.add("");
        report.add("## Drain throughput (backlog of due jobs, no-op handler)");
        report.add("");
        report.add("| Scenario | Jobs | Instances x threads | Wall time | Throughput |");
        report.add("|---|---:|---:|---:|---:|");
        drain("no-op, 1 instance", JOBS, "bench.noop", 0, 1, 8);
        drain("no-op, 4 instances", JOBS, "bench.noop", 0, 4, 8);

        report.add("");
        report.add("## Drain throughput with simulated I/O (" + IO_WORK_MS + " ms per job)");
        report.add("");
        report.add("| Scenario | Jobs | Instances x threads | Wall time | Throughput | Pool-bound ceiling |");
        report.add("|---|---:|---:|---:|---:|---:|");
        drain("I/O, 8 threads", IO_JOBS, "bench.io", IO_WORK_MS, 1, 8);
        drain("I/O, 32 threads", IO_JOBS, "bench.io", IO_WORK_MS, 1, 32);

        report.add("");
        report.add("## Latency under steady load (" + LATENCY_RATE + " jobs/s for " + LATENCY_SECONDS
                + " s, 1 instance x 16 threads, no-op handler)");
        report.add("");
        report.add("| Poll interval | Jobs | Submit->start p50 | p95 | p99 | Submit->done p50 | p95 | p99 | max |");
        report.add("|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
        latency(Duration.ofMillis(100));
        latency(Duration.ofSeconds(1));

        String text = String.join(System.lineSeparator(), report);
        System.out.println(System.lineSeparator() + text + System.lineSeparator());
        Path out = Path.of("target", "benchmark", "results.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text + System.lineSeparator());
    }

    // ------------------------------------------------------------------ scenarios

    /**
     * Exercises every code path once before measuring, so the numbers reflect steady state rather
     * than JIT compilation and first-use initialisation (Hibernate, connection pool, Jackson).
     * Not recorded.
     */
    private void warmUp() throws Exception {
        List<String> measured = new ArrayList<>(report);
        enqueueThroughput(2_000, 8);
        drain("warm-up", 2_000, "bench.noop", 0, 1, 8);
        report.clear();
        report.addAll(measured);
        report.add("");
        report.add("After an unrecorded warm-up (2,000 submissions and a 2,000-job drain).");
    }

    private void commitBaseline(int threads) throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS bench_commit (id BIGSERIAL PRIMARY KEY, at TIMESTAMPTZ NOT NULL)");
        int perThread = 2_000 / threads;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    jdbc.update("INSERT INTO bench_commit (at) VALUES (now())"); // auto-commit: one commit each
                }
                return null;
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.MINUTES)).isTrue();
        double seconds = (System.nanoTime() - start) / 1e9;
        int commits = perThread * threads;
        report.add("- %d thread(s): %,d commits in %.2f s -> **%,.0f commits/s** (%.1f ms per commit per thread)"
                .formatted(threads, commits, seconds, commits / seconds, seconds * 1000 * threads / commits));
        jdbc.execute("DROP TABLE bench_commit");
    }

    private void enqueueThroughput(int jobs, int clients) throws Exception {
        resetState();
        ExecutorService threads = Executors.newFixedThreadPool(clients);
        SubmitJobRequest request = new SubmitJobRequest("bench.noop", JsonNodeFactory.instance.objectNode(), null, null,
                Instant.now().plusSeconds(3600)); // not due: measure the insert path only
        long start = System.nanoTime();
        for (int c = 0; c < clients; c++) {
            threads.submit(() -> {
                for (int i = 0; i < jobs / clients; i++) {
                    jobService.submit(request, null);
                }
                return null;
            });
        }
        threads.shutdown();
        assertThat(threads.awaitTermination(10, TimeUnit.MINUTES)).isTrue();
        double seconds = (System.nanoTime() - start) / 1e9;
        long inserted = count("SELECT count(*) FROM jobs");
        report.add("");
        report.add("%,d jobs submitted in %.2f s -> **%,.0f jobs/s**".formatted(inserted, seconds, inserted / seconds));
    }

    private void drain(String name, int jobs, String type, int workMs, int instances, int threadsPerInstance)
            throws Exception {
        resetState();
        jdbc.update("""
                INSERT INTO jobs (id, type, payload, status, priority, attempts, max_attempts, run_at, created_at, updated_at)
                SELECT gen_random_uuid(), ?, CAST(? AS jsonb), 'PENDING', 0, 0, 3, now(), now(), now()
                  FROM generate_series(1, ?)
                """, type, "{\"workMs\": " + workMs + "}", jobs);

        try (Workers ignored = startWorkers(instances, threadsPerInstance, Duration.ofMillis(100))) {
            awaitAllSucceeded(jobs, Duration.ofMinutes(10));
        }

        double wallSeconds = jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM max(finished_at) - min(started_at)) FROM job_attempts", Double.class);
        double throughput = jobs / wallSeconds;
        String row = "| %s | %,d | %d x %d | %.2f s | **%,.0f jobs/s** |".formatted(
                name, jobs, instances, threadsPerInstance, wallSeconds, throughput);
        if (workMs > 0) {
            row += " %,d jobs/s |".formatted(instances * threadsPerInstance * 1000 / workMs);
        }
        report.add(row);
    }

    private void latency(Duration pollInterval) throws Exception {
        resetState();
        int total = LATENCY_RATE * LATENCY_SECONDS;
        SubmitJobRequest request = new SubmitJobRequest("bench.noop", JsonNodeFactory.instance.objectNode(), null, null, null);
        try (Workers ignored = startWorkers(1, 16, pollInterval)) {
            long intervalNanos = 1_000_000_000L / LATENCY_RATE;
            long next = System.nanoTime();
            for (int i = 0; i < total; i++) {
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    TimeUnit.NANOSECONDS.sleep(sleep);
                }
                jobService.submit(request, null);
                next += intervalNanos;
            }
            awaitAllSucceeded(total, Duration.ofMinutes(2));
        }
        Map<String, Object> stats = jdbc.queryForMap("""
                SELECT percentile_cont(0.50) WITHIN GROUP (ORDER BY start_ms) AS s50,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY start_ms) AS s95,
                       percentile_cont(0.99) WITHIN GROUP (ORDER BY start_ms) AS s99,
                       percentile_cont(0.50) WITHIN GROUP (ORDER BY done_ms)  AS d50,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY done_ms)  AS d95,
                       percentile_cont(0.99) WITHIN GROUP (ORDER BY done_ms)  AS d99,
                       max(done_ms) AS dmax
                  FROM (SELECT EXTRACT(EPOCH FROM a.started_at - j.created_at) * 1000 AS start_ms,
                               EXTRACT(EPOCH FROM a.finished_at - j.created_at) * 1000 AS done_ms
                          FROM job_attempts a JOIN jobs j ON j.id = a.job_id) t
                """);
        report.add("| %d ms | %,d | %s | %s | %s | %s | %s | %s | %s |".formatted(pollInterval.toMillis(), total,
                ms(stats.get("s50")), ms(stats.get("s95")), ms(stats.get("s99")),
                ms(stats.get("d50")), ms(stats.get("d95")), ms(stats.get("d99")), ms(stats.get("dmax"))));
    }

    // ------------------------------------------------------------------ helpers

    /** N simulated instances, each polling on its own thread with a fixed delay like @Scheduled. */
    private Workers startWorkers(int instances, int threads, Duration pollInterval) {
        JobSchedulerProperties props = TestProperties.with(Map.of(
                "jobs.worker.pool-size", String.valueOf(threads),
                "jobs.worker.queue-capacity", String.valueOf(threads),
                "jobs.poller.batch-size", "50"));
        Workers workers = new Workers();
        for (int i = 0; i < instances; i++) {
            ThreadPoolTaskExecutor executor = new ExecutorConfig().jobWorkerExecutor(props);
            executor.initialize();
            workers.executors.add(executor);
            WorkerPool pool = new WorkerPool(executor, jobRunner, jobQueue, props, new SimpleMeterRegistry());
            JobPoller poller = new JobPoller(jobQueue, pool, new WorkerIdentity("bench-" + i), jobMetrics, props);
            workers.loops.submit(() -> {
                while (workers.running.get()) {
                    poller.pollOnce();
                    Thread.sleep(pollInterval.toMillis());
                }
                return null;
            });
        }
        return workers;
    }

    private static final class Workers implements AutoCloseable {
        final AtomicBoolean running = new AtomicBoolean(true);
        final ExecutorService loops = Executors.newCachedThreadPool();
        final List<ThreadPoolTaskExecutor> executors = new ArrayList<>();

        @Override
        public void close() throws InterruptedException {
            running.set(false);
            loops.shutdown();
            loops.awaitTermination(30, TimeUnit.SECONDS);
            executors.forEach(ThreadPoolTaskExecutor::shutdown);
        }
    }

    private void awaitAllSucceeded(long expected, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(100))
                .until(() -> count("SELECT count(*) FROM jobs WHERE status = 'SUCCEEDED'") == expected);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static String ms(Object value) {
        return String.format(Locale.ROOT, "%.0f ms", ((Number) value).doubleValue());
    }

    private void header() throws IOException {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        report.add("# Queue benchmark results");
        report.add("");
        report.add("Measured on " + Instant.now() + " by `QueueBenchmark` (`./mvnw verify -Pbenchmark`).");
        report.add("");
        report.add("| Environment | |");
        report.add("|---|---|");
        report.add("| CPU | " + cpuName() + " (" + Runtime.getRuntime().availableProcessors() + " logical cores) |");
        report.add("| Memory | %.1f GB total, %.1f GB free at start |".formatted(
                os.getTotalMemorySize() / 1e9, os.getFreeMemorySize() / 1e9));
        report.add("| OS | " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " |");
        report.add("| JVM | " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version") + " |");
        report.add("| Database | " + jdbc.queryForObject("SELECT split_part(version(), ',', 1)", String.class)
                + (System.getenv("TEST_DB_URL") != null ? ", local server" : ", Testcontainers") + " |");
    }

    private static String cpuName() throws IOException {
        Path cpuinfo = Path.of("/proc/cpuinfo");
        if (Files.exists(cpuinfo)) {
            return Files.readAllLines(cpuinfo).stream().filter(line -> line.startsWith("model name"))
                    .map(line -> line.substring(line.indexOf(':') + 1).strip()).findFirst().orElse("unknown");
        }
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process reg = new ProcessBuilder("reg", "query",
                    "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v", "ProcessorNameString").start();
            String output = new String(reg.getInputStream().readAllBytes());
            int marker = output.indexOf("REG_SZ");
            if (marker >= 0) {
                return output.substring(marker + "REG_SZ".length()).strip();
            }
        }
        return System.getProperty("os.arch");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BenchmarkHandlers {

        @Bean
        JobHandler benchmarkNoopHandler() {
            return new JobHandler() {
                @Override
                public String type() {
                    return "bench.noop";
                }

                @Override
                public void handle(JobContext context) {
                }
            };
        }

        /** Simulates I/O-bound work (an HTTP call, an email send) by sleeping {@code workMs}. */
        @Bean
        JobHandler benchmarkIoHandler() {
            return new JobHandler() {
                @Override
                public String type() {
                    return "bench.io";
                }

                @Override
                public void handle(JobContext context) throws InterruptedException {
                    Thread.sleep(context.payload().path("workMs").asLong());
                }
            };
        }
    }
}
