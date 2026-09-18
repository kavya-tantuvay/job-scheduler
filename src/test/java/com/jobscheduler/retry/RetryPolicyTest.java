package com.jobscheduler.retry;

import com.jobscheduler.worker.NonRetryableJobException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    private static final Duration BASE = Duration.ofSeconds(2);
    private static final Duration MAX = Duration.ofSeconds(60);

    @ParameterizedTest(name = "attempt {0} -> ceiling {1} ms")
    @CsvSource({"1, 2000", "2, 4000", "3, 8000", "4, 16000", "5, 32000", "6, 60000", "7, 60000", "40, 60000"})
    void ceilingDoublesPerAttemptAndIsCapped(int attempt, long expectedMillis) {
        RetryPolicy policy = new RetryPolicy(BASE, MAX, () -> 0.5);
        assertThat(policy.ceiling(attempt)).isEqualTo(expectedMillis);
    }

    @Test
    void ceilingDoesNotOverflowForHugeAttemptNumbers() {
        RetryPolicy policy = new RetryPolicy(BASE, MAX, () -> 0.5);
        assertThat(policy.ceiling(Integer.MAX_VALUE)).isEqualTo(MAX.toMillis());
    }

    @Test
    void equalJitterKeepsDelayInUpperHalfOfCeiling() {
        // random() = 0 gives the smallest possible delay, random() -> 1 the largest.
        assertThat(new RetryPolicy(BASE, MAX, () -> 0.0).backoff(3)).isEqualTo(Duration.ofMillis(4000));
        assertThat(new RetryPolicy(BASE, MAX, () -> 0.999_999_9).backoff(3)).isEqualTo(Duration.ofMillis(8000));
    }

    @Test
    void jitterSpreadsDelaysForTheSameAttempt() {
        RetryPolicy policy = new RetryPolicy(BASE, MAX, new java.util.Random(42)::nextDouble);
        long distinct = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> policy.backoff(4))
                .peek(delay -> assertThat(delay).isBetween(Duration.ofMillis(8000), Duration.ofMillis(16000)))
                .distinct()
                .count();
        assertThat(distinct).isGreaterThan(90);
    }

    @Test
    void transientFailureIsRetriedWhileAttemptsRemain() {
        RetryPolicy policy = new RetryPolicy(BASE, MAX, () -> 0.0);
        RetryDecision decision = policy.decide(1, 3, new IllegalStateException("boom"));
        assertThat(decision).isEqualTo(new RetryDecision.Retry(Duration.ofMillis(1000)));
    }

    @Test
    void lastAttemptFailureIsDeadLettered() {
        RetryPolicy policy = new RetryPolicy(BASE, MAX, () -> 0.0);
        assertThat(policy.decide(3, 3, new IllegalStateException("boom"))).isInstanceOf(RetryDecision.DeadLetter.class);
    }

    @Test
    void nonRetryableFailureFailsImmediatelyEvenWithAttemptsLeft() {
        RetryPolicy policy = new RetryPolicy(BASE, MAX, () -> 0.0);
        assertThat(policy.decide(1, 5, new NonRetryableJobException("bad payload"))).isInstanceOf(RetryDecision.Fail.class);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new RetryPolicy(Duration.ZERO, MAX, () -> 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(MAX, BASE, () -> 0.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
