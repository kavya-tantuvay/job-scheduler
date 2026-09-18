package com.jobscheduler.job;

import java.time.Instant;
import java.util.Map;

/**
 * A point-in-time snapshot of the queue.
 *
 * @param countsByStatus       number of jobs in each status (every status present, zero if none)
 * @param dueNow               PENDING jobs whose run_at has passed, i.e. waiting only for a free worker
 * @param oldestDueAgeSeconds  how long the oldest due job has been waiting; null if none are due.
 *                             A steadily growing value means workers can't keep up.
 */
public record QueueStats(Map<JobStatus, Long> countsByStatus, long dueNow, Double oldestDueAgeSeconds, Instant asOf) {
}
