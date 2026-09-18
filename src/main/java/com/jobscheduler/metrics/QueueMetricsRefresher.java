package com.jobscheduler.metrics;

import com.jobscheduler.job.JobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes the queue-depth gauges on a timer, so a metrics scrape (possibly every few seconds,
 * from several dashboards) never triggers COUNT queries against the jobs table itself.
 */
@Slf4j
@Component
public class QueueMetricsRefresher {

    private final JobService jobService;
    private final JobMetrics jobMetrics;

    public QueueMetricsRefresher(JobService jobService, JobMetrics jobMetrics) {
        this.jobService = jobService;
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(fixedDelayString = "${jobs.metrics.refresh-interval:10s}")
    public void refresh() {
        try {
            jobMetrics.updateQueue(jobService.stats());
        } catch (RuntimeException e) {
            log.warn("Could not refresh queue metrics: {}", e.toString());
        }
    }
}
