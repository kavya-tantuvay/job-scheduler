package com.jobscheduler.job;

import com.jobscheduler.common.dto.ApiError;
import com.jobscheduler.common.dto.PageResponse;
import com.jobscheduler.job.dto.JobResponse;
import com.jobscheduler.job.dto.SubmitJobRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@Tag(name = "Jobs", description = "Submit, inspect and cancel jobs")
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @Operation(summary = "Submit a job to the queue",
            description = "Send an Idempotency-Key header to make retries of this call safe: repeating the "
                    + "same request with the same key returns the original job instead of enqueuing a duplicate.")
    @ApiResponse(responseCode = "201", description = "Job enqueued")
    @ApiResponse(responseCode = "200", description = "Replay: a job was already submitted with this Idempotency-Key",
            headers = @Header(name = IDEMPOTENT_REPLAYED_HEADER, description = "true"))
    @ApiResponse(responseCode = "400", description = "Invalid request or unknown job type",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Idempotency-Key reused with a different request",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    public ResponseEntity<JobResponse> submit(
            @Parameter(description = "Optional client-chosen key (1-255 printable ASCII chars) that de-duplicates submissions")
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody SubmitJobRequest request) {
        SubmissionResult result = jobService.submit(request, idempotencyKey);
        JobResponse body = JobResponse.from(result.job());
        if (!result.created()) {
            return ResponseEntity.ok().header(IDEMPOTENT_REPLAYED_HEADER, "true").body(body);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(result.job().getId()).toUri();
        return ResponseEntity.created(location).body(body);
    }

    @Operation(summary = "Get a job's status, attempts and last error")
    @ApiResponse(responseCode = "200", description = "The job")
    @ApiResponse(responseCode = "404", description = "No such job",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(jobService.get(id));
    }

    @Operation(summary = "List jobs, newest first, optionally filtered by status and type",
            description = "Example: ?status=DEAD to browse the dead-letter queue.")
    @GetMapping
    public PageResponse<JobResponse> list(
            @RequestParam(required = false) JobStatus status,
            @RequestParam(required = false) String type,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(jobService.list(status, type, pageable), JobResponse::from);
    }

    @Operation(summary = "Cancel a job that has not started yet")
    @ApiResponse(responseCode = "200", description = "Job cancelled")
    @ApiResponse(responseCode = "404", description = "No such job",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Job is no longer PENDING",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/cancel")
    public JobResponse cancel(@PathVariable UUID id) {
        return JobResponse.from(jobService.cancel(id));
    }
}
