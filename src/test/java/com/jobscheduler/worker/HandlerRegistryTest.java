package com.jobscheduler.worker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandlerRegistryTest {

    private static JobHandler handler(String type) {
        return new JobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void handle(JobContext context) {
            }
        };
    }

    @Test
    void findsHandlersByType() {
        JobHandler email = handler("email.send");
        JobHandler report = handler("report");
        HandlerRegistry registry = new HandlerRegistry(List.of(report, email));

        assertThat(registry.find("email.send")).containsSame(email);
        assertThat(registry.find("report")).containsSame(report);
        assertThat(registry.find("missing")).isEmpty();
        assertThat(registry.supports("report")).isTrue();
        assertThat(registry.supports("missing")).isFalse();
        assertThat(registry.registeredTypes()).containsExactly("email.send", "report");
    }

    @Test
    void rejectsTwoHandlersForTheSameType() {
        assertThatThrownBy(() -> new HandlerRegistry(List.of(handler("report"), handler("report"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate handlers for job type 'report'");
    }

    @Test
    void rejectsInvalidTypeNames() {
        assertThatThrownBy(() -> new HandlerRegistry(List.of(handler("Send Email"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid job type");
    }

    @Test
    void emptyRegistryIsAllowed() {
        assertThat(new HandlerRegistry(List.of()).registeredTypes()).isEmpty();
    }
}
