package com.jobscheduler.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** The record of one finished execution attempt of a job. Immutable once written. */
@Getter
@Entity
@Table(name = "job_attempts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobAttempt {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(nullable = false, updatable = false)
    private int attempt;

    @Column(name = "worker_id", nullable = false, updatable = false)
    private String workerId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false, updatable = false)
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private AttemptOutcome outcome;

    @Column(updatable = false)
    private String error;

    @Column(name = "stack_trace", updatable = false)
    private String stackTrace;

    static JobAttempt succeeded(ClaimedJob job, Instant startedAt, Instant finishedAt) {
        return of(job, startedAt, finishedAt, AttemptOutcome.SUCCEEDED, null, null);
    }

    static JobAttempt failed(ClaimedJob job, AttemptFailure failure) {
        return of(job, failure.startedAt(), failure.finishedAt(), AttemptOutcome.FAILED,
                failure.error(), failure.stackTrace());
    }

    private static JobAttempt of(ClaimedJob job, Instant startedAt, Instant finishedAt, AttemptOutcome outcome,
                                 String error, String stackTrace) {
        JobAttempt attempt = new JobAttempt();
        attempt.jobId = job.id();
        attempt.attempt = job.attempt();
        attempt.workerId = job.workerId();
        attempt.startedAt = startedAt;
        attempt.finishedAt = finishedAt;
        attempt.outcome = outcome;
        attempt.error = error;
        attempt.stackTrace = stackTrace;
        return attempt;
    }

    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }
}
