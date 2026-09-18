package com.jobscheduler.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI jobSchedulerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Job Scheduler API")
                .version("v1")
                .description("Submit, query and cancel jobs processed by a PostgreSQL-backed task queue."));
    }
}
