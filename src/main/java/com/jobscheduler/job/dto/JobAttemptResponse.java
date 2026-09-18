package com.jobscheduler.job.dto;

import com.jobscheduler.job.AttemptOutcome;
import com.jobscheduler.job.JobAttempt;

import java.time.Instant;

public record JobAttemptResponse(
        int attempt,
        AttemptOutcome outcome,
        String workerId,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        String error,
        String stackTrace
) {

    public static JobAttemptResponse from(JobAttempt attempt) {
        return new JobAttemptResponse(
                attempt.getAttempt(),
                attempt.getOutcome(),
                attempt.getWorkerId(),
                attempt.getStartedAt(),
                attempt.getFinishedAt(),
                attempt.duration().toMillis(),
                attempt.getError(),
                attempt.getStackTrace());
    }
}
