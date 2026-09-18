package com.jobscheduler.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
public class JobService {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[\\x21-\\x7E]{1," + MAX_IDEMPOTENCY_KEY_LENGTH + "}$");

    private final JobRepository jobRepository;
    private final JobAttemptRepository attemptRepository;
    private final HandlerRegistry handlerRegistry;
    private final JobSchedulerProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public JobService(JobRepository jobRepository, JobAttemptRepository attemptRepository,
                      HandlerRegistry handlerRegistry,
                      JobSchedulerProperties properties, Clock clock, ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
        this.handlerRegistry = handlerRegistry;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * Enqueues a job. With an idempotency key, repeating the same request returns the job created
     * the first time instead of enqueuing a duplicate; concurrent duplicates are resolved by the
     * database's unique index, not by a check-then-insert in Java.
     */
    @Transactional
    public SubmissionResult submit(SubmitJobRequest request, String idempotencyKey) {
        if (!handlerRegistry.supports(request.type())) {
            throw new InvalidRequestException("Unknown job type '%s'. Registered types: %s"
                    .formatted(request.type(), handlerRegistry.registeredTypes()));
        }
        String key = normaliseIdempotencyKey(idempotencyKey);
        Job job = Job.create(
                request.type(),
                JobPayloads.normalise(request.payload()),
                Objects.requireNonNullElse(request.priority(), 0),
                Objects.requireNonNullElse(request.maxAttempts(), properties.submission().defaultMaxAttempts()),
                Objects.requireNonNullElseGet(request.runAt(), clock::instant),
                key);

        if (key == null) {
            return created(jobRepository.save(job));
        }

        UUID id = UUID.randomUUID();
        int inserted = jobRepository.insertIfIdempotencyKeyUnused(id, job.getType(), toJson(job.getPayload()),
                job.getPriority(), job.getMaxAttempts(), job.getRunAt(), key);
        if (inserted == 1) {
            return created(get(id));
        }
        Job existing = jobRepository.findByIdempotencyKey(key)
                .orElseThrow(() -> new IllegalStateException("Idempotency key conflict but no job found for " + key));
        return replay(existing, request, job.getPayload());
    }

    private static SubmissionResult created(Job job) {
        log.info("Submitted job {} type={} priority={} runAt={}",
                job.getId(), job.getType(), job.getPriority(), job.getRunAt());
        return new SubmissionResult(job, true);
    }

    @Transactional(readOnly = true)
    public Job get(UUID id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Job " + id + " not found"));
    }

    /** Finished attempts of a job, oldest first. */
    @Transactional(readOnly = true)
    public List<JobAttempt> attempts(UUID jobId) {
        if (!jobRepository.existsById(jobId)) {
            throw new ResourceNotFoundException("Job " + jobId + " not found");
        }
        return attemptRepository.findByJobIdOrderByAttemptAsc(jobId);
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

    /**
     * Dead-letter redrive: gives a DEAD or FAILED job {@code additionalAttempts} more attempts and
     * puts it back in the queue, e.g. after the bug or outage that killed it has been fixed.
     */
    @Transactional
    public Job retry(UUID id, Integer additionalAttempts) {
        Job job = get(id);
        if (job.getStatus() != JobStatus.DEAD && job.getStatus() != JobStatus.FAILED) {
            throw new ConflictException("Only DEAD or FAILED jobs can be retried; job %s is %s"
                    .formatted(id, job.getStatus()));
        }
        int extra = Objects.requireNonNullElse(additionalAttempts, properties.submission().defaultMaxAttempts());
        if (jobRepository.requeueFinishedUnsuccessfully(id, extra) == 0) {
            throw new ConflictException("Job %s changed state before it could be retried".formatted(id));
        }
        log.info("Re-queued {} job {} with {} more attempt(s)", job.getStatus(), id, extra);
        return get(id);
    }

    /**
     * A key may only be reused for the same request. Fields the client left out are not compared,
     * since their defaults (e.g. runAt = now) legitimately differ between calls.
     */
    private SubmissionResult replay(Job existing, SubmitJobRequest request, JsonNode payload) {
        boolean sameRequest = existing.getType().equals(request.type())
                && existing.getPayload().equals(payload)
                && (request.priority() == null || request.priority() == existing.getPriority())
                && (request.maxAttempts() == null || request.maxAttempts() == existing.getMaxAttempts())
                && (request.runAt() == null || sameInstant(request.runAt(), existing.getRunAt()));
        if (!sameRequest) {
            throw new ConflictException("Idempotency-Key '%s' was already used for a different request (job %s)"
                    .formatted(existing.getIdempotencyKey(), existing.getId()));
        }
        log.debug("Idempotent replay of job {} for key {}", existing.getId(), existing.getIdempotencyKey());
        return new SubmissionResult(existing, false);
    }

    /** PostgreSQL stores microseconds. */
    private static boolean sameInstant(Instant requested, Instant stored) {
        return requested.truncatedTo(ChronoUnit.MICROS).equals(stored.truncatedTo(ChronoUnit.MICROS));
    }

    private static String normaliseIdempotencyKey(String key) {
        if (key == null) {
            return null;
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new InvalidRequestException("Idempotency-Key must be 1-%d printable ASCII characters without spaces"
                    .formatted(MAX_IDEMPOTENCY_KEY_LENGTH));
        }
        return key;
    }

    private String toJson(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise job payload", e);
        }
    }
}
