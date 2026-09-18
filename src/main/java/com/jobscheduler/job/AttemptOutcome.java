package com.jobscheduler.job;

public enum AttemptOutcome {
    SUCCEEDED,
    /** The handler threw; the job was retried, dead-lettered or failed depending on the retry policy. */
    FAILED,
    /** The worker never reported back within the lease timeout (crashed, hung or lost the database). */
    LEASE_EXPIRED
}
