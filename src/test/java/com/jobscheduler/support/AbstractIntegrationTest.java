package com.jobscheduler.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests against a real PostgreSQL: {@code SKIP LOCKED}, JSONB,
 * {@code ON CONFLICT} and the Flyway migrations can't be faked with an in-memory database.
 *
 * <p>By default a PostgreSQL 16 container is started once and shared by all test classes
 * (Testcontainers "singleton container" pattern; Ryuk removes it when the JVM exits). Set
 * {@code TEST_DB_URL} (plus {@code TEST_DB_USERNAME}/{@code TEST_DB_PASSWORD}) to use an existing
 * database instead, e.g. on a machine without Docker. Its tables are truncated between tests.
 *
 * <p>Background loops are disabled ({@code jobs.scheduling.enabled=false}); tests drive the poller,
 * reaper and recurring scheduler explicitly so they are deterministic.
 */
@SpringBootTest(properties = {
        "jobs.scheduling.enabled=false",
        "jobs.retry.base-delay=20ms",
        "jobs.retry.max-delay=100ms",
        "jobs.metrics.refresh-interval=1h"
})
@Import(TestHandlers.class)
public abstract class AbstractIntegrationTest {

    private static final String EXTERNAL_DB_URL = System.getenv("TEST_DB_URL");

    private static final PostgreSQLContainer<?> POSTGRES = EXTERNAL_DB_URL != null ? null : startPostgres();

    private static PostgreSQLContainer<?> startPostgres() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_DB_URL);
            registry.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", "jobscheduler"));
            registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "jobscheduler"));
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value != null ? value : fallback;
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TestHandlers.CountingJobHandler countingHandler;

    @BeforeEach
    protected void resetState() {
        jdbc.execute("TRUNCATE jobs, job_attempts, recurring_jobs");
        countingHandler.reset();
    }
}
