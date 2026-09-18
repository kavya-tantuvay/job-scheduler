package com.jobscheduler.job;

import com.jobscheduler.common.dto.PageResponse;
import com.jobscheduler.job.dto.JobResponse;
import com.jobscheduler.job.dto.SubmitJobRequest;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@Tag(name = "Jobs")
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @Operation(summary = "Submit a job to the queue")
    @PostMapping
    public ResponseEntity<JobResponse> submit(@Valid @RequestBody SubmitJobRequest request) {
        Job job = jobService.submit(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(job.getId()).toUri();
        return ResponseEntity.created(location).body(JobResponse.from(job));
    }

    @Operation(summary = "Get a job's status, attempts and last error")
    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(jobService.get(id));
    }

    @Operation(summary = "List jobs, optionally filtered by status and type")
    @GetMapping
    public PageResponse<JobResponse> list(
            @RequestParam(required = false) JobStatus status,
            @RequestParam(required = false) String type,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(jobService.list(status, type, pageable), JobResponse::from);
    }

    @Operation(summary = "Cancel a job that has not started yet")
    @PostMapping("/{id}/cancel")
    public JobResponse cancel(@PathVariable UUID id) {
        return JobResponse.from(jobService.cancel(id));
    }
}
