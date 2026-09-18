package com.jobscheduler.job;

import java.util.regex.Pattern;

/**
 * Rules for job type names. Types are plain strings rather than an enum on purpose: a new
 * job type is added by registering a new handler bean, without touching the domain model.
 */
public final class JobType {

    public static final int MAX_LENGTH = 100;

    /** Lower-case words separated by '.', '_' or '-', e.g. {@code email.send}. */
    public static final String PATTERN = "^[a-z][a-z0-9]*([._-][a-z0-9]+)*$";

    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    private JobType() {
    }

    public static boolean isValid(String type) {
        return type != null && type.length() <= MAX_LENGTH && COMPILED.matcher(type).matches();
    }
}
