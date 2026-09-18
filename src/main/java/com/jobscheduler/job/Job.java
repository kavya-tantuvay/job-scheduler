package com.jobscheduler.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobscheduler.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

/**
 * A unit of work in the queue. The {@code jobs} table <em>is</em> the queue: workers claim rows
 * with {@code SELECT ... FOR UPDATE SKIP LOCKED}. After creation, state changes are made through
 * guarded UPDATE statements in {@link JobRepository} rather than by mutating this entity, so every
 * transition is atomic in the database.
 */
@Getter
@Entity
@Table(name = "jobs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job extends BaseEntity {

    /** Selects the {@code JobHandler} that executes this job. */
    @Column(nullable = false, length = 100)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    /** Higher values are claimed first. */
    @Column(nullable = false)
    private int priority;

    /** Number of times the job has been claimed for execution. */
    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    /** Earliest time the job may run; pushed forward by retry backoff. */
    @Column(name = "run_at", nullable = false)
    private Instant runAt;

    /** When the current claim started; used to detect jobs whose worker died. */
    @Column(name = "locked_at")
    private Instant lockedAt;

    /** Id of the scheduler instance holding the claim. */
    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "last_error")
    private String lastError;

    public static Job create(String type, JsonNode payload, int priority, int maxAttempts, Instant runAt) {
        Job job = new Job();
        job.type = Objects.requireNonNull(type, "type");
        job.payload = Objects.requireNonNull(payload, "payload");
        job.priority = priority;
        job.maxAttempts = maxAttempts;
        job.runAt = Objects.requireNonNull(runAt, "runAt");
        job.status = JobStatus.PENDING;
        job.attempts = 0;
        return job;
    }
}
