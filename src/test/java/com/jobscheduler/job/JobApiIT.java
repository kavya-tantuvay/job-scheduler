package com.jobscheduler.job;

import com.jayway.jsonpath.JsonPath;
import com.jobscheduler.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The REST contract, through the full MVC stack and a real database. */
class JobApiIT extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private com.jobscheduler.worker.JobPoller jobPoller;

    private MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String submit(String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void submitReturnsCreatedJobWithLocation() throws Exception {
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"counting\",\"payload\":{\"to\":\"a@b.c\"},\"priority\":7}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/jobs/")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.priority").value(7))
                .andExpect(jsonPath("$.maxAttempts").value(3))
                .andExpect(jsonPath("$.payload.to").value("a@b.c"));
    }

    @Test
    void invalidSubmissionReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"Not Valid\",\"priority\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(2)));
    }

    @Test
    void unknownTypeIsRejected() throws Exception {
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Unknown job type 'nope'")));
    }

    @Test
    void idempotencyKeyReplaysAndRejectsMismatchedReuse() throws Exception {
        String body = "{\"type\":\"counting\",\"payload\":{\"invoice\":1}}";
        MvcResult first = mvc.perform(post("/api/jobs").header("Idempotency-Key", "inv-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/jobs").header("Idempotency-Key", "inv-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));

        mvc.perform(post("/api/jobs").header("Idempotency-Key", "inv-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"counting\",\"payload\":{\"invoice\":2}}"))
                .andExpect(status().isConflict());
    }

    @Test
    void listFiltersByStatusAndType() throws Exception {
        submit("{\"type\":\"counting\"}");
        submit("{\"type\":\"log\"}");
        String cancelled = submit("{\"type\":\"counting\"}");
        mvc.perform(post("/api/jobs/{id}/cancel", cancelled)).andExpect(status().isOk());

        mvc.perform(get("/api/jobs").param("type", "counting"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/jobs").param("status", "CANCELLED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(cancelled));
        mvc.perform(get("/api/jobs").param("status", "BOGUS")).andExpect(status().isBadRequest());
    }

    @Test
    void cancellingTwiceConflictsAndUnknownJobIsNotFound() throws Exception {
        String id = submit("{\"type\":\"counting\"}");
        mvc.perform(post("/api/jobs/{id}/cancel", id)).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/jobs/{id}/cancel", id)).andExpect(status().isConflict());
        mvc.perform(get("/api/jobs/{id}", "00000000-0000-0000-0000-000000000000")).andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/{id}", "not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void failedJobExposesAttemptHistoryAndCanBeRetried() throws Exception {
        String id = submit("{\"type\":\"flaky\",\"payload\":{\"permanent\":true}}");
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() -> {
            jobPoller.pollOnce();
            return jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?::uuid", String.class, id).equals("FAILED");
        });

        mvc.perform(get("/api/jobs/{id}/attempts", id))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].outcome").value("FAILED"))
                .andExpect(jsonPath("$[0].error", containsString("Simulated permanent failure")))
                .andExpect(jsonPath("$[0].stackTrace", containsString("FlakyJobHandler")));

        mvc.perform(post("/api/jobs/{id}/retry", id).contentType(MediaType.APPLICATION_JSON).content("{\"additionalAttempts\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.maxAttempts").value(3));
    }

    @Test
    void statsCountJobsByStatus() throws Exception {
        submit("{\"type\":\"counting\"}");
        submit("{\"type\":\"counting\"}");

        mvc.perform(get("/api/jobs/stats"))
                .andExpect(jsonPath("$.countsByStatus.PENDING").value(2))
                .andExpect(jsonPath("$.countsByStatus.DEAD").value(0))
                .andExpect(jsonPath("$.dueNow").value(2));
    }

    @Test
    void recurringDefinitionsCanBeCreatedListedAndDeleted() throws Exception {
        MvcResult created = mvc.perform(post("/api/recurring").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"nightly\",\"type\":\"counting\",\"cron\":\"0 0 2 * * *\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/recurring").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"nightly\",\"type\":\"counting\",\"cron\":\"@daily\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/recurring").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"type\":\"counting\",\"cron\":\"not a cron\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/recurring")).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(delete("/api/recurring/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/recurring")).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void operationalEndpointsAreUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/jobs']").exists());
    }
}
