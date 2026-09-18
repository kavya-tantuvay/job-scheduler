package com.jobscheduler.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jobscheduler.common.exception.ConflictException;
import com.jobscheduler.common.exception.InvalidRequestException;
import com.jobscheduler.common.exception.ResourceNotFoundException;
import com.jobscheduler.config.JobSchedulerProperties;
import com.jobscheduler.job.dto.SubmitJobRequest;
import com.jobscheduler.worker.HandlerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class JobService {

    private final JobRepository jobRepository;
    private final HandlerRegistry handlerRegistry;
    private final JobSchedulerProperties properties;
    private final Clock clock;

    public JobService(JobRepository jobRepository, HandlerRegistry handlerRegistry,
                      JobSchedulerProperties properties, Clock clock) {
        this.jobRepository = jobRepository;
        this.handlerRegistry = handlerRegistry;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Job submit(SubmitJobRequest request) {
        if (!handlerRegistry.supports(request.type())) {
            throw new InvalidRequestException("Unknown job type '%s'. Registered types: %s"
                    .formatted(request.type(), handlerRegistry.registeredTypes()));
        }
        Job job = Job.create(
                request.type(),
                normalisePayload(request.payload()),
                Objects.requireNonNullElse(request.priority(), 0),
                Objects.requireNonNullElse(request.maxAttempts(), properties.submission().defaultMaxAttempts()),
                Objects.requireNonNullElseGet(request.runAt(), clock::instant));
        Job saved = jobRepository.save(job);
        log.info("Submitted job {} type={} priority={} runAt={}",
                saved.getId(), saved.getType(), saved.getPriority(), saved.getRunAt());
        return saved;
    }

    @Transactional(readOnly = true)
    public Job get(UUID id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Job " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public Page<Job> list(JobStatus status, String type, Pageable pageable) {
        Specification<Job> filter = Specification.allOf(
                JobSpecifications.hasStatus(status), JobSpecifications.hasType(type));
        return jobRepository.findAll(filter, pageable);
    }

    /**
     * Cancels a job that hasn't started. The status check below gives a clear error message,
     * but the conditional UPDATE is what makes it safe: if a worker claims the job between the
     * read and the update, the update matches 0 rows and we report the conflict.
     */
    @Transactional
    public Job cancel(UUID id) {
        Job job = get(id);
        if (!job.getStatus().canTransitionTo(JobStatus.CANCELLED)) {
            throw new ConflictException("Job %s is %s and can no longer be cancelled".formatted(id, job.getStatus()));
        }
        if (jobRepository.cancelIfPending(id) == 0) {
            throw new ConflictException("Job %s was claimed or finished before it could be cancelled".formatted(id));
        }
        log.info("Cancelled job {}", id);
        return get(id);
    }

    private JsonNode normalisePayload(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return JsonNodeFactory.instance.objectNode();
        }
        if (!payload.isObject()) {
            throw new InvalidRequestException("payload must be a JSON object");
        }
        return payload;
    }
}
