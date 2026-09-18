package com.jobscheduler.worker;

import com.jobscheduler.job.JobType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Maps job type → handler. Spring injects every {@link JobHandler} bean; the registry fails
 * application startup on invalid or duplicate type names rather than at execution time.
 */
@Slf4j
@Component
public class HandlerRegistry {

    private final Map<String, JobHandler> handlersByType;

    public HandlerRegistry(List<JobHandler> handlers) {
        Map<String, JobHandler> byType = new TreeMap<>();
        for (JobHandler handler : handlers) {
            String type = handler.type();
            if (!JobType.isValid(type)) {
                throw new IllegalStateException("Handler " + handler.getClass().getName()
                        + " declares invalid job type '" + type + "'; expected " + JobType.PATTERN);
            }
            JobHandler existing = byType.putIfAbsent(type, handler);
            if (existing != null) {
                throw new IllegalStateException("Duplicate handlers for job type '" + type + "': "
                        + existing.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
        this.handlersByType = Collections.unmodifiableMap(byType);
        log.info("Registered job handlers for types {}", handlersByType.keySet());
    }

    public Optional<JobHandler> find(String type) {
        return Optional.ofNullable(handlersByType.get(type));
    }

    public boolean supports(String type) {
        return handlersByType.containsKey(type);
    }

    /** Registered types in alphabetical order. */
    public Set<String> registeredTypes() {
        return handlersByType.keySet();
    }
}
