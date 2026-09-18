package com.jobscheduler.support;

import com.jobscheduler.config.JobSchedulerProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

/** Builds {@link JobSchedulerProperties} for unit tests exactly as Spring Boot would bind them. */
public final class TestProperties {

    private TestProperties() {
    }

    public static JobSchedulerProperties defaults() {
        return with(Map.of());
    }

    /** @param overrides e.g. {@code Map.of("jobs.worker.pool-size", "4")} */
    public static JobSchedulerProperties with(Map<String, String> overrides) {
        return new Binder(new MapConfigurationPropertySource(overrides))
                .bindOrCreate("jobs", Bindable.of(JobSchedulerProperties.class));
    }
}
