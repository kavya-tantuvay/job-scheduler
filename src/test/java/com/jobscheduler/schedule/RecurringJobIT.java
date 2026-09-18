package com.jobscheduler.schedule;

import com.jobscheduler.schedule.dto.CreateRecurringJobRequest;
import com.jobscheduler.support.AbstractIntegrationTest;
import com.jobscheduler.support.TestHandlers.CountingJobHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RecurringJobIT extends AbstractIntegrationTest {

    @Autowired
    private RecurringJobService recurringJobService;
    @Autowired
    private RecurringJobScheduler scheduler;

    private UUID defineHourly(String name) {
        return recurringJobService.create(new CreateRecurringJobRequest(
                name, CountingJobHandler.TYPE, null, "0 0 * * * *", true)).getId();
    }

    private void makeDue(UUID id, String ago) {
        jdbc.update("UPDATE recurring_jobs SET next_run_at = date_trunc('second', now()) - CAST(? AS interval) WHERE id = ?",
                ago, id);
    }

    private long jobsEnqueuedBy(UUID definitionId) {
        return jdbc.queryForObject("SELECT count(*) FROM jobs WHERE idempotency_key LIKE ?", Long.class,
                "recurring:" + definitionId + ":%");
    }

    @Test
    void dueDefinitionEnqueuesOneJobAndAdvancesToTheNextFireTime() {
        UUID id = defineHourly("hourly-report");
        makeDue(id, "5 seconds");

        assertThat(scheduler.tickOnce()).isEqualTo(1);
        assertThat(jobsEnqueuedBy(id)).isEqualTo(1);

        Instant next = recurringJobService.get(id).getNextRunAt();
        assertThat(next).isAfter(Instant.now());
        assertThat(scheduler.tickOnce()).isZero();
        assertThat(jobsEnqueuedBy(id)).isEqualTo(1);
    }

    @Test
    void manyMissedWindowsAreCoalescedIntoASingleRun() {
        UUID id = defineHourly("catch-up");
        makeDue(id, "10 hours"); // ten hourly runs missed while "down"

        scheduler.tickOnce();

        assertThat(jobsEnqueuedBy(id)).isEqualTo(1);
        assertThat(recurringJobService.get(id).getNextRunAt()).isAfter(Instant.now());
    }

    @Test
    void pausedDefinitionDoesNotFire() {
        UUID id = defineHourly("paused");
        recurringJobService.pause(id);
        makeDue(id, "5 seconds");

        assertThat(scheduler.tickOnce()).isZero();
        assertThat(jobsEnqueuedBy(id)).isZero();
    }

    @Test
    void concurrentTicksFromManyInstancesFireEachWindowOnce() throws Exception {
        List<UUID> ids = List.of(defineHourly("a"), defineHourly("b"), defineHourly("c"));
        ids.forEach(id -> makeDue(id, "5 seconds"));

        int instances = 8;
        CyclicBarrier startTogether = new CyclicBarrier(instances);
        ExecutorService threads = Executors.newFixedThreadPool(instances);
        List<Future<Integer>> fired = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            fired.add(threads.submit(() -> {
                startTogether.await();
                return scheduler.tickOnce();
            }));
        }
        int total = 0;
        for (Future<Integer> result : fired) {
            total += result.get(30, TimeUnit.SECONDS);
        }
        threads.shutdown();

        assertThat(total).isEqualTo(3);
        ids.forEach(id -> assertThat(jobsEnqueuedBy(id)).isEqualTo(1));
    }
}
