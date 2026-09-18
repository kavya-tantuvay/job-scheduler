package com.jobscheduler.retry;

import java.time.Duration;

/** What to do with a job after a failed attempt. */
public sealed interface RetryDecision {

    /** Put the job back in the queue, eligible again after {@code delay}. */
    record Retry(Duration delay) implements RetryDecision {
    }

    /** Every allowed attempt has failed: move the job to the dead-letter state (DEAD). */
    record DeadLetter() implements RetryDecision {
    }

    /** The failure is permanent; retrying cannot help (FAILED). */
    record Fail() implements RetryDecision {
    }
}
