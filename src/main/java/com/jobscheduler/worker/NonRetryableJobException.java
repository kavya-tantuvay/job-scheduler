package com.jobscheduler.worker;

/**
 * Thrown by a handler when retrying cannot help (e.g. the payload is invalid). The job goes
 * straight to FAILED instead of being retried. Any other exception is treated as transient.
 */
public class NonRetryableJobException extends RuntimeException {

    public NonRetryableJobException(String message) {
        super(message);
    }

    public NonRetryableJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
