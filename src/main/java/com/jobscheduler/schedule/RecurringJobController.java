package com.jobscheduler.schedule;

import com.jobscheduler.common.dto.ApiError;
import com.jobscheduler.schedule.dto.CreateRecurringJobRequest;
import com.jobscheduler.schedule.dto.RecurringJobResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Tag(name = "Recurring jobs", description = "Cron schedules that enqueue a job each time they fire")
@RestController
@RequestMapping("/api/recurring")
public class RecurringJobController {

    private final RecurringJobService recurringJobService;

    public RecurringJobController(RecurringJobService recurringJobService) {
        this.recurringJobService = recurringJobService;
    }

    @Operation(summary = "Define a recurring (cron) job")
    @ApiResponse(responseCode = "201", description = "Schedule created")
    @ApiResponse(responseCode = "400", description = "Invalid cron expression, payload or job type",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Name already in use",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    public ResponseEntity<RecurringJobResponse> create(@Valid @RequestBody CreateRecurringJobRequest request) {
        RecurringJob recurringJob = recurringJobService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(recurringJob.getId()).toUri();
        return ResponseEntity.created(location).body(RecurringJobResponse.from(recurringJob));
    }

    @Operation(summary = "List recurring job definitions")
    @GetMapping
    public List<RecurringJobResponse> list() {
        return recurringJobService.list().stream().map(RecurringJobResponse::from).toList();
    }

    @Operation(summary = "Get a recurring job definition")
    @GetMapping("/{id}")
    public RecurringJobResponse get(@PathVariable UUID id) {
        return RecurringJobResponse.from(recurringJobService.get(id));
    }

    @Operation(summary = "Pause a schedule; it stops enqueuing jobs until resumed")
    @PostMapping("/{id}/pause")
    public RecurringJobResponse pause(@PathVariable UUID id) {
        return RecurringJobResponse.from(recurringJobService.pause(id));
    }

    @Operation(summary = "Resume a paused schedule from its next fire time")
    @PostMapping("/{id}/resume")
    public RecurringJobResponse resume(@PathVariable UUID id) {
        return RecurringJobResponse.from(recurringJobService.resume(id));
    }

    @Operation(summary = "Delete a schedule; jobs it already enqueued are unaffected")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        recurringJobService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
