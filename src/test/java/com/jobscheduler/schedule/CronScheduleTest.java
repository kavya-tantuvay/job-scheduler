package com.jobscheduler.schedule;

import com.jobscheduler.common.exception.InvalidRequestException;
import com.jobscheduler.support.TestProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CronScheduleTest {

    private static CronSchedule scheduleIn(String zone) {
        return new CronSchedule(TestProperties.with(Map.of("jobs.recurring.zone", zone)));
    }

    @Test
    void nextFireTimeIsStrictlyAfterTheGivenInstant() {
        CronSchedule schedule = scheduleIn("UTC");
        Instant onBoundary = Instant.parse("2026-03-01T10:15:00Z");

        assertThat(schedule.nextAfter("0 */15 * * * *", onBoundary)).isEqualTo(Instant.parse("2026-03-01T10:30:00Z"));
        assertThat(schedule.nextAfter("0 */15 * * * *", onBoundary.minusSeconds(1))).isEqualTo(onBoundary);
    }

    @Test
    void supportsMacros() {
        assertThat(scheduleIn("UTC").nextAfter("@daily", Instant.parse("2026-03-01T10:15:00Z")))
                .isEqualTo(Instant.parse("2026-03-02T00:00:00Z"));
    }

    @Test
    void evaluatesInTheConfiguredZone() {
        // 09:00 in Kolkata (UTC+05:30) is 03:30 UTC.
        assertThat(scheduleIn("Asia/Kolkata").nextAfter("0 0 9 * * *", Instant.parse("2026-03-01T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-01T03:30:00Z"));
    }

    @Test
    void rejectsMalformedExpressions() {
        assertThatThrownBy(() -> scheduleIn("UTC").validate("*/5 * * * *", Instant.now()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Invalid cron expression");
    }

    @Test
    void rejectsExpressionsThatNeverFire() {
        assertThatThrownBy(() -> scheduleIn("UTC").validate("0 0 0 30 2 *", Instant.now()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("never fires");
    }
}
