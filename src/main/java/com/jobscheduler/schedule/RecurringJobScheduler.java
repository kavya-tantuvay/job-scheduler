package com.jobscheduler.schedule;

import com.jobscheduler.config.JobSchedulerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The tick that turns due cron definitions into queued jobs. */
@Slf4j
@Component
public class RecurringJobScheduler {

    private final RecurringJobService recurringJobService;
    private final int batchSize;

    public RecurringJobScheduler(RecurringJobService recurringJobService, JobSchedulerProperties properties) {
        this.recurringJobService = recurringJobService;
        this.batchSize = properties.recurring().batchSize();
    }

    @Scheduled(fixedDelayString = "${jobs.recurring.interval:1s}")
    public void tick() {
        try {
            tickOnce();
        } catch (RuntimeException e) {
            log.error("Recurring job tick failed; will retry on the next tick", e);
        }
    }

    /** @return number of definitions fired; each batch is its own transaction */
    public int tickOnce() {
        int total = 0;
        int fired;
        do {
            fired = recurringJobService.fireDue(batchSize);
            total += fired;
        } while (fired == batchSize);
        return total;
    }
}
