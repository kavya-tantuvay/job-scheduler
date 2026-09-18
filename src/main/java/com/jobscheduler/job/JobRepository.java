package com.jobscheduler.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Queue access. Every status transition is a single guarded UPDATE whose WHERE clause states
 * the expected current state, so concurrent actors (API, workers, reaper) can't overwrite each
 * other: whoever loses the race simply updates 0 rows.
 */
public interface JobRepository extends JpaRepository<Job, UUID>, JpaSpecificationExecutor<Job> {

    /** PENDING → CANCELLED. Returns 0 if the job doesn't exist or was already claimed/finished. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE jobs
               SET status = 'CANCELLED', updated_at = now()
             WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int cancelIfPending(@Param("id") UUID id);
}
