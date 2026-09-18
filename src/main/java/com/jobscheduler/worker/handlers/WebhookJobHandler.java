package com.jobscheduler.worker.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobscheduler.worker.JobContext;
import com.jobscheduler.worker.JobHandler;
import com.jobscheduler.worker.NonRetryableJobException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

/**
 * Delivers a webhook: POSTs a JSON body to a URL. A realistic job for a queue, since the receiver
 * can be slow or down, which is exactly what retries with backoff and rate limits are for.
 *
 * <p>Payload: {@code {"url": "https://example.com/hook", "body": {...}}}. Each request carries an
 * {@code Idempotency-Key} header (the job id) so receivers can ignore redeliveries.
 *
 * <ul>
 *   <li>2xx: success</li>
 *   <li>408, 429, 5xx, timeouts, connection errors: transient, retried with backoff</li>
 *   <li>other 4xx, missing/invalid URL: permanent, the job goes to FAILED</li>
 * </ul>
 *
 * <p>In production, restrict which hosts may be called; otherwise job submitters can make the
 * server reach internal addresses (SSRF).
 */
@Slf4j
@Component
public class WebhookJobHandler implements JobHandler {

    public static final String TYPE = "webhook";

    private final RestClient restClient;

    public WebhookJobHandler(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = builder.requestFactory(requestFactory).build();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(JobContext context) {
        URI url = parseUrl(context.payload().path("url").asText(null));
        JsonNode body = context.payload().path("body");

        HttpStatusCode status = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", context.jobId().toString())
                .body(body.isMissingNode() ? "{}" : body.toString())
                .exchange((request, response) -> response.getStatusCode());

        if (status.is2xxSuccessful()) {
            log.debug("Webhook job {} delivered to {} ({})", context.jobId(), url, status.value());
            return;
        }
        String message = "Webhook " + url + " responded " + status.value();
        if (status.is5xxServerError() || status.value() == 408 || status.value() == 429) {
            throw new IllegalStateException(message); // transient: retried
        }
        throw new NonRetryableJobException(message);
    }

    private static URI parseUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new NonRetryableJobException("payload.url is required");
        }
        try {
            URI uri = URI.create(url);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new NonRetryableJobException("payload.url must be http or https: " + url);
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new NonRetryableJobException("payload.url is not a valid URL: " + url, e);
        }
    }
}
