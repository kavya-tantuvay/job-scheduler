package com.jobscheduler.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RecurringJobRepository extends JpaRepository<RecurringJob, UUID> {

    boolean existsByName(String name);

    List<RecurringJob> findAllByOrderByNameAsc();

    /**
     * Locks enabled definitions that are due. {@code SKIP LOCKED} lets every instance run the
     * recurring scheduler at once: each definition is handled by exactly one of them per tick, and
     * the row lock is held until the enqueue and the {@code next_run_at} advance have committed.
     */
    @Query(value = """
            SELECT *
              FROM recurring_jobs
             WHERE enabled
               AND next_run_at <= :now
             ORDER BY next_run_at
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<RecurringJob> lockDue(@Param("now") Instant now, @Param("limit") int limit);
}
