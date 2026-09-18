package com.jobscheduler.common.exception;

/** The request is valid but conflicts with the resource's current state. Maps to HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
