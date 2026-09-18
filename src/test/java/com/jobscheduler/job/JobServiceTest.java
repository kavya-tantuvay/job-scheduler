package com.jobscheduler.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jobscheduler.common.exception.ConflictException;
import com.jobscheduler.common.exception.InvalidRequestException;
import com.jobscheduler.common.exception.ResourceNotFoundException;
import com.jobscheduler.job.dto.SubmitJobRequest;
import com.jobscheduler.support.TestProperties;
import com.jobscheduler.worker.HandlerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-01T12:00:00Z");

    @Mock
    private JobRepository jobRepository;
    @Mock
    private JobAttemptRepository attemptRepository;
    @Mock
    private HandlerRegistry handlerRegistry;

    private JobService service;

    @BeforeEach
    void setUp() {
        service = new JobService(jobRepository, attemptRepository, handlerRegistry, TestProperties.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
    }

    private static SubmitJobRequest request(String type) {
        return new SubmitJobRequest(type, null, null, null, null);
    }

    @Test
    void submitAppliesDefaults() {
        when(handlerRegistry.supports("log")).thenReturn(true);
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SubmissionResult result = service.submit(request("log"), null);

        assertThat(result.created()).isTrue();
        Job job = result.job();
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPriority()).isZero();
        assertThat(job.getMaxAttempts()).isEqualTo(3);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getRunAt()).isEqualTo(NOW);
        assertThat(job.getPayload()).isEqualTo(JsonNodeFactory.instance.objectNode());
    }

    @Test
    void submitRejectsUnknownType() {
        when(handlerRegistry.supports("nope")).thenReturn(false);
        when(handlerRegistry.registeredTypes()).thenReturn(Set.of("log"));

        assertThatThrownBy(() -> service.submit(request("nope"), null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Unknown job type 'nope'");
        verify(jobRepository, never()).save(any());
    }

    @Test
    void submitRejectsNonObjectPayload() {
        when(handlerRegistry.supports("log")).thenReturn(true);
        var array = JsonNodeFactory.instance.arrayNode().add(1);

        assertThatThrownBy(() -> service.submit(new SubmitJobRequest("log", array, null, null, null), null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    void submitRejectsMalformedIdempotencyKey() {
        when(handlerRegistry.supports("log")).thenReturn(true);

        assertThatThrownBy(() -> service.submit(request("log"), "has space"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void repeatedIdempotencyKeyReturnsTheOriginalJob() {
        when(handlerRegistry.supports("log")).thenReturn(true);
        Job existing = Job.create("log", JsonNodeFactory.instance.objectNode(), 0, 3, NOW, "key-1");
        when(jobRepository.insertIfIdempotencyKeyUnused(any(), eq("log"), anyString(), anyInt(), anyInt(), any(), eq("key-1")))
                .thenReturn(0);
        when(jobRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        SubmissionResult result = service.submit(request("log"), "key-1");

        assertThat(result.created()).isFalse();
        assertThat(result.job()).isSameAs(existing);
    }

    @Test
    void idempotencyKeyReusedForADifferentRequestConflicts() {
        when(handlerRegistry.supports("log")).thenReturn(true);
        Job existing = Job.create("log", JsonNodeFactory.instance.objectNode().put("a", 1), 0, 3, NOW, "key-1");
        when(jobRepository.insertIfIdempotencyKeyUnused(any(), any(), any(), anyInt(), anyInt(), any(), any())).thenReturn(0);
        when(jobRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        var different = new SubmitJobRequest("log", JsonNodeFactory.instance.objectNode().put("a", 2), null, null, null);
        assertThatThrownBy(() -> service.submit(different, "key-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already used for a different request");
    }

    @Test
    void newIdempotencyKeyInsertsWithOnConflict() {
        when(handlerRegistry.supports("log")).thenReturn(true);
        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        when(jobRepository.insertIfIdempotencyKeyUnused(id.capture(), eq("log"), eq("{}"), eq(0), eq(3), eq(NOW), eq("key-2")))
                .thenReturn(1);
        Job inserted = Job.create("log", JsonNodeFactory.instance.objectNode(), 0, 3, NOW, "key-2");
        when(jobRepository.findById(any())).thenReturn(Optional.of(inserted));

        SubmissionResult result = service.submit(request("log"), "key-2");

        assertThat(result.created()).isTrue();
        verify(jobRepository).findById(id.getValue());
    }

    @Test
    void cancelRejectsJobsThatAlreadyStarted() {
        UUID id = UUID.randomUUID();
        Job running = Job.create("log", JsonNodeFactory.instance.objectNode(), 0, 3, NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(running, "status", JobStatus.RUNNING);
        when(jobRepository.findById(id)).thenReturn(Optional.of(running));

        assertThatThrownBy(() -> service.cancel(id)).isInstanceOf(ConflictException.class);
        verify(jobRepository, never()).cancelIfPending(any());
    }

    @Test
    void cancelReportsLostRaceWithAWorker() {
        UUID id = UUID.randomUUID();
        when(jobRepository.findById(id))
                .thenReturn(Optional.of(Job.create("log", JsonNodeFactory.instance.objectNode(), 0, 3, NOW)));
        when(jobRepository.cancelIfPending(id)).thenReturn(0); // a worker claimed it in between

        assertThatThrownBy(() -> service.cancel(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("claimed or finished");
    }

    @Test
    void getUnknownJobIsNotFound() {
        UUID id = UUID.randomUUID();
        when(jobRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void retryOnlyAppliesToDeadOrFailedJobs() {
        UUID id = UUID.randomUUID();
        when(jobRepository.findById(id))
                .thenReturn(Optional.of(Job.create("log", JsonNodeFactory.instance.objectNode(), 0, 3, NOW)));

        assertThatThrownBy(() -> service.retry(id, 2))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only DEAD or FAILED");
    }
}
