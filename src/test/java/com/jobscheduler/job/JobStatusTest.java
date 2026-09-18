package com.jobscheduler.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class JobStatusTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "PENDING, RUNNING", "PENDING, CANCELLED",
            "RUNNING, SUCCEEDED", "RUNNING, FAILED", "RUNNING, DEAD", "RUNNING, PENDING",
            "FAILED, PENDING", "DEAD, PENDING"})
    void allowedTransitions(JobStatus from, JobStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "{0} -/-> {1}")
    @CsvSource({
            "PENDING, SUCCEEDED", "PENDING, DEAD",
            "RUNNING, CANCELLED",
            "SUCCEEDED, PENDING", "SUCCEEDED, RUNNING",
            "CANCELLED, PENDING", "DEAD, RUNNING", "FAILED, SUCCEEDED"})
    void forbiddenTransitions(JobStatus from, JobStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @Test
    void onlyPendingAndRunningAreActive() {
        assertThat(JobStatus.PENDING.isTerminal()).isFalse();
        assertThat(JobStatus.RUNNING.isTerminal()).isFalse();
        assertThat(JobStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(JobStatus.FAILED.isTerminal()).isTrue();
        assertThat(JobStatus.DEAD.isTerminal()).isTrue();
        assertThat(JobStatus.CANCELLED.isTerminal()).isTrue();
    }
}
