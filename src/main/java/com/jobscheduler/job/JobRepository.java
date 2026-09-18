package com.jobscheduler.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Queue access. Every status transition is a single guarded UPDATE whose WHERE clause states
 * the expected current state, so concurrent actors (API, workers, reaper) can't overwrite each
 * other: whoever loses the race simply updates 0 rows.
 */
public interface JobRepository extends JpaRepository<Job, UUID>, JpaSpecificationExecutor<Job> {

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    /**
     * Inserts a keyed job unless the key is already taken. If a concurrent transaction is
     * inserting the same key, PostgreSQL waits for it to finish and then skips this insert, so a
     * duplicate is a normal "0 rows" outcome rather than a constraint-violation exception.
     *
     * @return 1 if inserted, 0 if a job with this key already exists
     */
    @Modifying
    @Query(value = """
            INSERT INTO jobs (id, type, payload, status, priority, attempts, max_attempts, run_at,
                              idempotency_key, created_at, updated_at)
            VALUES (:id, :type, CAST(:payload AS jsonb), 'PENDING', :priority, 0, :maxAttempts, :runAt,
                    :idempotencyKey, now(), now())
            ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
            """, nativeQuery = true)
    int insertIfIdempotencyKeyUnused(@Param("id") UUID id, @Param("type") String type,
                                     @Param("payload") String payload, @Param("priority") int priority,
                                     @Param("maxAttempts") int maxAttempts, @Param("runAt") Instant runAt,
                                     @Param("idempotencyKey") String idempotencyKey);

    /**
     * Atomically claims up to {@code limit} due jobs for {@code workerId}.
     *
     * <p>The CTE picks due PENDING rows in priority order and row-locks them with
     * {@code FOR UPDATE}. {@code SKIP LOCKED} makes a concurrent claimer skip rows that another
     * transaction has already locked instead of blocking on them, so N pollers each walk away with
     * a disjoint set of jobs. The outer UPDATE flips the locked rows to RUNNING in the same
     * statement; by the time the locks are released (at commit) the rows no longer match
     * {@code status = 'PENDING'}, so nobody can claim them again.
     *
     * <p>{@code attempts} is incremented here, at claim time, so an attempt counts even if the
     * worker dies before reporting back. Must run inside a transaction.
     */
    @Query(value = """
            WITH due AS (
                SELECT id
                  FROM jobs
                 WHERE status = 'PENDING'
                   AND run_at <= now()
                 ORDER BY priority DESC, run_at
                 LIMIT :limit
                   FOR UPDATE SKIP LOCKED
            )
            UPDATE jobs j
               SET status     = 'RUNNING',
                   attempts   = j.attempts + 1,
                   locked_at  = now(),
                   locked_by  = :workerId,
                   updated_at = now()
              FROM due
             WHERE j.id = due.id
            RETURNING j.*
            """, nativeQuery = true)
    List<Job> claimDueJobs(@Param("limit") int limit, @Param("workerId") String workerId);

    // ---- Outcome of a claim. Each update is fenced on (locked_by, attempts): it only applies
    // ---- while this exact claim still owns the job. Returns the number of rows changed (0 or 1).

    /** RUNNING → SUCCEEDED. Lock columns are kept as a record of who ran the job last. */
    @Modifying
    @Query(value = """
            UPDATE jobs
               SET status = 'SUCCEEDED', updated_at = now()
             WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId AND attempts = :attempt
            """, nativeQuery = true)
    int markSucceeded(@Param("id") UUID id, @Param("workerId") String workerId, @Param("attempt") int attempt);

    /**
     * RUNNING → PENDING after a failed attempt, eligible again after the backoff delay. Uses the
     * database clock so every instance agrees on when the job becomes due.
     */
    @Modifying
    @Query(value = """
            UPDATE jobs
               SET status = 'PENDING', last_error = :error,
                   run_at = now() + (:delayMillis * INTERVAL '1 millisecond'),
                   locked_at = NULL, locked_by = NULL, updated_at = now()
             WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId AND attempts = :attempt
            """, nativeQuery = true)
    int scheduleRetry(@Param("id") UUID id, @Param("workerId") String workerId, @Param("attempt") int attempt,
                      @Param("error") String error, @Param("delayMillis") long delayMillis);

    /** RUNNING → DEAD: retries exhausted. The job stays in the table as a dead letter for inspection. */
    @Modifying
    @Query(value = """
            UPDATE jobs
               SET status = 'DEAD', last_error = :error, updated_at = now()
             WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId AND attempts = :attempt
            """, nativeQuery = true)
    int markDead(@Param("id") UUID id, @Param("workerId") String workerId, @Param("attempt") int attempt,
                 @Param("error") String error);

    /** RUNNING → FAILED: a permanent (non-retryable) failure. */
    @Modifying
    @Query(value = """
            UPDATE jobs
               SET status = 'FAILED', last_error = :error, updated_at = now()
             WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId AND attempts = :attempt
            """, nativeQuery = true)
    int markFailed(@Param("id") UUID id, @Param("workerId") String workerId, @Param("attempt") int attempt,
                   @Param("error") String error);

    /**
     * RUNNING → PENDING for a claim that was never executed (e.g. the worker pool rejected it).
     * The attempt is given back because the handler never ran.
     */
    @Modifying
    @Query(value = """
            UPDATE jobs
               SET status = 'PENDING', attempts = attempts - 1,
                   locked_at = NULL, locked_by = NULL, updated_at = now()
             WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId AND attempts = :attempt
            """, nativeQuery = true)
    int releaseClaim(@Param("id") UUID id, @Param("workerId") String workerId, @Param("attempt") int attempt);

    /** PENDING → CANCELLED. Returns 0 if the job doesn't exist or was already claimed/finished. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE jobs
               SET status = 'CANCELLED', updated_at = now()
             WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int cancelIfPending(@Param("id") UUID id);
}
