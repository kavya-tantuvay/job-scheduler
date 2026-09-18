package com.jobscheduler.job;

import java.time.Instant;

/**
 * Details of a failed attempt, as recorded in the job's history.
 *
 * @param error      one-line summary, also stored as the job's {@code last_error}
 * @param stackTrace truncated stack trace, or null when there is no exception worth showing
 */
public record AttemptFailure(Instant startedAt, Instant finishedAt, String error, String stackTrace) {
}
