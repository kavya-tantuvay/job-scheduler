package com.jobscheduler.job;

/**
 * @param created false when an existing job was returned for a repeated Idempotency-Key
 */
public record SubmissionResult(Job job, boolean created) {
}
