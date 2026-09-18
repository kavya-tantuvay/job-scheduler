package com.jobscheduler.common.exception;

/** A semantically invalid request that passed bean validation (e.g. unknown job type). Maps to HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
