package com.jobscheduler.schedule;

import com.jobscheduler.common.exception.ConflictException;
import com.jobscheduler.common.exception.InvalidRequestException;
import com.jobscheduler.common.exception.ResourceNotFoundException;
import com.jobscheduler.job.JobPayloads;
import com.jobscheduler.schedule.dto.CreateRecurringJobRequest;
import com.jobscheduler.worker.HandlerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Manages cron definitions. Each definition enqueues a concrete job whenever it fires. */
@Slf4j
@Service
public class RecurringJobService {

    private final RecurringJobRepository recurringJobRepository;
    private final HandlerRegistry handlerRegistry;
    private final CronSchedule cronSchedule;
    private final Clock clock;

    public RecurringJobService(RecurringJobRepository recurringJobRepository, HandlerRegistry handlerRegistry,
                               CronSchedule cronSchedule, Clock clock) {
        this.recurringJobRepository = recurringJobRepository;
        this.handlerRegistry = handlerRegistry;
        this.cronSchedule = cronSchedule;
        this.clock = clock;
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
