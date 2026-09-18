package com.jobscheduler.worker;

import com.jobscheduler.config.JobSchedulerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * The id this process writes into {@code locked_by} when it claims jobs. It must be unique per
 * running process (not just per host), otherwise a restarted instance could complete a claim
 * made by its predecessor.
 */
@Slf4j
@Component
public class WorkerIdentity {

    private final String id;

    public WorkerIdentity(JobSchedulerProperties properties) {
        String configured = properties.worker().instanceId();
        this.id = configured != null && !configured.isBlank() ? configured.strip() : generate();
        log.info("Worker instance id: {}", id);
    }

    public String id() {
        return id;
    }

    private static String generate() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return host + "-" + ProcessHandle.current().pid() + "-" + suffix;
    }
}
