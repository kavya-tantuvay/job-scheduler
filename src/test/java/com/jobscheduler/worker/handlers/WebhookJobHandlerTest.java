package com.jobscheduler.worker.handlers;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobscheduler.worker.JobContext;
import com.jobscheduler.worker.NonRetryableJobException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the handler against a real local HTTP server. */
class WebhookJobHandlerTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastIdempotencyKey = new AtomicReference<>();
    private final WebhookJobHandler handler = new WebhookJobHandler(RestClient.builder());

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            int status = Integer.parseInt(exchange.getRequestURI().getPath().substring(1));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private JobContext context(String path) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("url", "http://127.0.0.1:" + server.getAddress().getPort() + path);
        payload.putObject("body").put("event", "order.paid");
        return new JobContext(UUID.randomUUID(), WebhookJobHandler.TYPE, payload, 1, 3);
    }

    @Test
    void deliversJsonBodyWithIdempotencyKey() {
        JobContext context = context("/200");

        assertThatCode(() -> handler.handle(context)).doesNotThrowAnyException();
        assertThat(lastBody.get()).isEqualTo("{\"event\":\"order.paid\"}");
        assertThat(lastIdempotencyKey.get()).isEqualTo(context.jobId().toString());
    }

    @Test
    void serverErrorsAndThrottlingAreRetryable() {
        for (String status : new String[]{"/500", "/503", "/429", "/408"}) {
            assertThatThrownBy(() -> handler.handle(context(status)))
                    .isNotInstanceOf(NonRetryableJobException.class)
                    .hasMessageContaining("responded " + status.substring(1));
        }
    }

    @Test
    void clientErrorsArePermanent() {
        assertThatThrownBy(() -> handler.handle(context("/404"))).isInstanceOf(NonRetryableJobException.class);
        assertThatThrownBy(() -> handler.handle(context("/400"))).isInstanceOf(NonRetryableJobException.class);
    }

    @Test
    void missingOrUnsupportedUrlIsPermanent() {
        var noUrl = new JobContext(UUID.randomUUID(), "webhook", JsonNodeFactory.instance.objectNode(), 1, 3);
        var ftp = new JobContext(UUID.randomUUID(), "webhook",
                JsonNodeFactory.instance.objectNode().put("url", "ftp://example.com/x"), 1, 3);

        assertThatThrownBy(() -> handler.handle(noUrl)).isInstanceOf(NonRetryableJobException.class);
        assertThatThrownBy(() -> handler.handle(ftp)).isInstanceOf(NonRetryableJobException.class);
    }

    @Test
    void connectionFailureIsRetryable() {
        server.stop(0);
        assertThatThrownBy(() -> handler.handle(context("/200"))).isNotInstanceOf(NonRetryableJobException.class);
    }
}
