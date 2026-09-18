package com.jobscheduler.support;

import com.jobscheduler.worker.JobContext;
import com.jobscheduler.worker.JobHandler;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@TestConfiguration(proxyBeanMethods = false)
public class TestHandlers {

    @Bean
    CountingJobHandler countingJobHandler() {
        return new CountingJobHandler();
    }

    /**
     * Records every execution per (job, attempt), which is what the exactly-once assertions check.
     * Payload options: {@code sleepMs} to simulate work, {@code failOnFirstAttempt} to force one retry.
     */
    public static class CountingJobHandler implements JobHandler {

        public static final String TYPE = "counting";

        private final Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public void handle(JobContext context) throws InterruptedException {
            executions.computeIfAbsent(key(context.jobId(), context.attempt()), k -> new AtomicInteger()).incrementAndGet();
            long sleepMs = context.payload().path("sleepMs").asLong(0);
            if (sleepMs > 0) {
                Thread.sleep(sleepMs);
            }
            if (context.payload().path("failOnFirstAttempt").asBoolean(false) && context.attempt() == 1) {
                throw new IllegalStateException("planned failure on attempt 1");
            }
        }

        public int executions(UUID jobId, int attempt) {
            AtomicInteger count = executions.get(key(jobId, attempt));
            return count == null ? 0 : count.get();
        }

        /** (job id + ":" + attempt) → number of times that attempt ran. */
        public Map<String, AtomicInteger> allExecutions() {
            return executions;
        }

        public int totalExecutions() {
            return executions.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        void reset() {
            executions.clear();
        }

        private static String key(UUID jobId, int attempt) {
            return jobId + ":" + attempt;
        }
    }
}
