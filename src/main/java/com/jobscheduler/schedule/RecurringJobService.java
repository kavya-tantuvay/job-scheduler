package com.jobscheduler.schedule;

import com.jobscheduler.common.exception.ConflictException;
import com.jobscheduler.common.exception.InvalidRequestException;
import com.jobscheduler.common.exception.ResourceNotFoundException;
import com.jobscheduler.config.JobSchedulerProperties;
import com.jobscheduler.config.JobSchedulerProperties.MisfirePolicy;
import com.jobscheduler.job.JobPayloads;
import com.jobscheduler.job.JobService;
import com.jobscheduler.schedule.dto.CreateRecurringJobRequest;
import com.jobscheduler.worker.HandlerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Manages cron definitions. Each definition enqueues a concrete job whenever it fires. */
@Slf4j
@Service
public class RecurringJobService {

    private final RecurringJobRepository recurringJobRepository;
    private final JobService jobService;
    private final HandlerRegistry handlerRegistry;
    private final CronSchedule cronSchedule;
    private final Clock clock;
    private final MisfirePolicy misfirePolicy;
    private final Duration misfireThreshold;

    public RecurringJobService(RecurringJobRepository recurringJobRepository, JobService jobService,
                               HandlerRegistry handlerRegistry, CronSchedule cronSchedule, Clock clock,
                               JobSchedulerProperties properties) {
        this.recurringJobRepository = recurringJobRepository;
        this.jobService = jobService;
        this.handlerRegistry = handlerRegistry;
        this.cronSchedule = cronSchedule;
        this.clock = clock;
        this.misfirePolicy = properties.recurring().misfirePolicy();
        this.misfireThreshold = properties.recurring().misfireThreshold();
    }

    /**
     * Fires up to {@code limit} due definitions. For each one, enqueuing the job and advancing
     * {@code next_run_at} commit together (or not at all) while the definition's row is locked, so a
     * fire time can be neither lost nor enqueued twice, even with several instances ticking at once.
     * The per-fire idempotency key is a second guard against duplicates.
     *
     * @return number of definitions processed
     */
    @Transactional
    public int fireDue(int limit) {
        Instant now = clock.instant();
        List<RecurringJob> due = recurringJobRepository.lockDue(now, limit);
        due.forEach(recurringJob -> fire(recurringJob, now));
        return due.size();
    }

    /**
     * Catch-up policy: a fire time up to {@code misfireThreshold} late is simply run late. Beyond
     * that it counts as missed (e.g. every instance was down), and the policy decides: FIRE_ONCE
     * runs once for the whole missed period, SKIP runs nothing. Either way the schedule then jumps
     * to the first fire time after now, so an outage never causes a burst of back-to-back runs.
     */
    private void fire(RecurringJob recurringJob, Instant now) {
        Instant scheduledFor = recurringJob.getNextRunAt();
        boolean missed = Duration.between(scheduledFor, now).compareTo(misfireThreshold) > 0;

        if (missed && misfirePolicy == MisfirePolicy.SKIP) {
            log.warn("Recurring job '{}' missed its run at {}; skipping (misfire policy SKIP)",
                    recurringJob.getName(), scheduledFor);
        } else {
            String key = "recurring:" + recurringJob.getId() + ":" + scheduledFor;
            boolean enqueued = jobService.enqueueOnce(recurringJob.getType(), recurringJob.getPayload(), scheduledFor, key);
            if (!enqueued) {
                log.warn("Recurring job '{}' run at {} was already enqueued", recurringJob.getName(), scheduledFor);
            } else if (missed) {
                log.warn("Recurring job '{}' missed its run at {}; running once now (misfire policy FIRE_ONCE)",
                        recurringJob.getName(), scheduledFor);
            } else {
                log.debug("Recurring job '{}' enqueued run for {}", recurringJob.getName(), scheduledFor);
            }
        }

        try {
            recurringJob.advanceTo(cronSchedule.nextAfter(recurringJob.getCron(), now));
        } catch (InvalidRequestException e) {
            // The cron was valid when saved but has no more fire times (e.g. a fixed year that passed).
            recurringJob.pause();
            log.warn("Recurring job '{}' paused: {}", recurringJob.getName(), e.getMessage());
        }
    }

    @Transactional
    public RecurringJob create(CreateRecurringJobRequest request) {
        if (!handlerRegistry.supports(request.type())) {
            throw new InvalidRequestException("Unknown job type '%s'. Registered types: %s"
                    .formatted(request.type(), handlerRegistry.registeredTypes()));
        }
        if (recurringJobRepository.existsByName(request.name())) {
            throw new ConflictException("A recurring job named '%s' already exists".formatted(request.name()));
        }
        String cron = request.cron().strip();
        Instant firstRunAt = cronSchedule.nextAfter(cron, clock.instant());
        RecurringJob recurringJob = recurringJobRepository.save(RecurringJob.create(
                request.name(), request.type(), JobPayloads.normalise(request.payload()), cron, firstRunAt,
                Objects.requireNonNullElse(request.enabled(), true)));
        log.info("Created recurring job '{}' ({}) type={} cron='{}' first run {}",
                recurringJob.getName(), recurringJob.getId(), recurringJob.getType(), cron, firstRunAt);
        return recurringJob;
    }

    @Transactional(readOnly = true)
    public List<RecurringJob> list() {
        return recurringJobRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public RecurringJob get(UUID id) {
        return recurringJobRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Recurring job " + id + " not found"));
    }

    @Transactional
    public RecurringJob pause(UUID id) {
        RecurringJob recurringJob = get(id);
        recurringJob.pause();
        log.info("Paused recurring job '{}'", recurringJob.getName());
        return recurringJob;
    }

    /** Resumes from the next fire time after now: runs missed while paused are not made up. */
    @Transactional
    public RecurringJob resume(UUID id) {
        RecurringJob recurringJob = get(id);
        if (!recurringJob.isEnabled()) {
            recurringJob.resume(cronSchedule.nextAfter(recurringJob.getCron(), clock.instant()));
            log.info("Resumed recurring job '{}', next run {}", recurringJob.getName(), recurringJob.getNextRunAt());
        }
        return recurringJob;
    }

    /** Deletes the definition. Jobs it already enqueued are unaffected. */
    @Transactional
    public void delete(UUID id) {
        RecurringJob recurringJob = get(id);
        recurringJobRepository.delete(recurringJob);
        log.info("Deleted recurring job '{}'", recurringJob.getName());
    }
}
