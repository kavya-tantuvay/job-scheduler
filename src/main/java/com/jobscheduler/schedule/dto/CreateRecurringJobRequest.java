package com.jobscheduler.schedule.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobscheduler.job.JobType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateRecurringJobRequest(

        @Schema(description = "Unique name for this schedule", example = "nightly-report")
        @NotBlank
        @Size(max = 100)
        String name,

        @Schema(description = "Job type to enqueue on each fire; must match a registered handler", example = "log")
        @NotBlank
        @Size(max = JobType.MAX_LENGTH)
        @Pattern(regexp = JobType.PATTERN, message = "must be lower-case words separated by '.', '_' or '-'")
        String type,

        @Schema(description = "Payload given to every enqueued job; defaults to {}")
        JsonNode payload,

        @Schema(description = "Spring cron expression with seconds: second minute hour day-of-month month "
                + "day-of-week, or a macro such as @hourly", example = "0 */5 * * * *")
        @NotBlank
        @Size(max = 120)
        String cron,

        @Schema(description = "Whether the schedule starts active; defaults to true")
        Boolean enabled
) {
}
