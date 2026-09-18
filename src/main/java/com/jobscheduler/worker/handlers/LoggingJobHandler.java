package com.jobscheduler.worker.handlers;

import com.jobscheduler.worker.JobContext;
import com.jobscheduler.worker.JobHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Logs the job payload. Payload: any JSON, e.g. {@code {"message": "hello"}}. */
@Slf4j
@Component
public class LoggingJobHandler implements JobHandler {

    public static final String TYPE = "log";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(JobContext context) {
        log.info("Job {} (attempt {}/{}) payload: {}",
                context.jobId(), context.attempt(), context.maxAttempts(), context.payload());
    }
}
