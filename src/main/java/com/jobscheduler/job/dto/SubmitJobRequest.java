package com.jobscheduler.job.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobscheduler.job.JobType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record SubmitJobRequest(

        @Schema(description = "Job type; must match a registered handler", example = "log")
        @NotBlank
        @Size(max = JobType.MAX_LENGTH)
        @Pattern(regexp = JobType.PATTERN, message = "must be lower-case words separated by '.', '_' or '-'")
        String type,

        @Schema(description = "Arbitrary JSON input for the handler; defaults to {}",
                example = "{\"message\": \"hello\"}")
        JsonNode payload,

        @Schema(description = "Higher runs first; defaults to 0", example = "5")
        @Min(0) @Max(100)
        Integer priority,

        @Schema(description = "Maximum execution attempts; defaults to jobs.submission.default-max-attempts")
        @Min(1) @Max(20)
        Integer maxAttempts,

        @Schema(description = "Earliest time to run (ISO-8601); defaults to now")
        Instant runAt
) {
}
