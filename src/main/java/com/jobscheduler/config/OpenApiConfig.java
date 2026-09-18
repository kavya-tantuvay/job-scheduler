package com.jobscheduler.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String DESCRIPTION = """
            A PostgreSQL-backed job queue: submit work over HTTP and a pool of workers runs it with
            retries, exponential backoff, dead-lettering and idempotent submission.

            **Try it**
            1. `POST /api/jobs` with `{"type": "log", "payload": {"message": "hello"}}`
            2. `GET /api/jobs/{id}` to watch it go `PENDING` → `RUNNING` → `SUCCEEDED`
            3. Submit `{"type": "flaky", "payload": {"failUntilAttempt": 99}, "maxAttempts": 3}` and inspect
               `GET /api/jobs/{id}/attempts` to see each retry's error and stack trace, then
               `GET /api/jobs?status=DEAD` for the dead-letter queue.

            Built-in job types: `log`, `sleep`, `flaky` (demo) and `webhook` (JSON POST to a URL).
            """;

    @Bean
    public OpenAPI jobSchedulerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Job Scheduler API")
                        .version("v1")
                        .description(DESCRIPTION))
                .externalDocs(new ExternalDocumentation()
                        .description("Source, architecture and design notes")
                        .url("https://github.com/kavya-tantuvay/job-scheduler"));
    }
}
