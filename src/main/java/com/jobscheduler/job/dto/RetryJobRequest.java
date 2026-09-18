package com.jobscheduler.job.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record RetryJobRequest(
        @Schema(description = "Further attempts to allow; defaults to jobs.submission.default-max-attempts",
                example = "3")
        @Min(1) @Max(20)
        Integer additionalAttempts
) {
}
