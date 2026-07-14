package com.anchr.core.integration.ai.client;

import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.common.model.ParseResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Authenticated asynchronous HTTP client for anchr-docling.
 */
@Component
public class DoclingClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String authorization;
    private final Duration pollInterval;
    private final Duration maxWait;
    private final int maxResubmits;

    public DoclingClient(
            @Value("${app.docling.base-url}") String baseUrl,
            @Value("${app.docling.api-token}") String apiToken,
            @Value("${app.docling.poll-interval:2s}") Duration pollInterval,
            @Value("${app.docling.max-wait:45m}") Duration maxWait,
            @Value("${app.docling.max-resubmits:2}") int maxResubmits
    ) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("app.docling.base-url must not be blank");
        }
        if (apiToken == null || apiToken.length() < 32) {
            throw new IllegalArgumentException("app.docling.api-token must contain at least 32 characters");
        }
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("app.docling.poll-interval must be positive");
        }
        if (maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
            throw new IllegalArgumentException("app.docling.max-wait must be positive");
        }
        if (maxResubmits < 0) {
            throw new IllegalArgumentException("app.docling.max-resubmits must not be negative");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.authorization = "Bearer " + apiToken;
        this.pollInterval = pollInterval;
        this.maxWait = maxWait;
        this.maxResubmits = maxResubmits;
    }

    public ParseResponse parse(ParseRequest request) {
        long deadlineNanos = System.nanoTime() + maxWait.toNanos();
        int resubmits = 0;
        JobEnvelope job = submitUntilAccepted(request, deadlineNanos);

        while (true) {
            ensureWithinDeadline(deadlineNanos);
            try {
                HttpResponse<String> response = send(buildRequest("GET", "/v1/jobs/" + job.jobId(), null));
                if (response.statusCode() == 404 && resubmits < maxResubmits) {
                    resubmits++;
                    job = submitUntilAccepted(request, deadlineNanos);
                    continue;
                }
                requireSuccess(response, "poll");
                JobEnvelope current = readJob(response.body());
                switch (current.normalizedStatus()) {
                    case "queued", "running" -> sleep(pollInterval, deadlineNanos);
                    case "succeeded" -> {
                        if (current.result() == null) {
                            throw new RuntimeException("docling succeeded without a result");
                        }
                        acknowledge(current.jobId());
                        return current.result();
                    }
                    case "failed" -> throw new RuntimeException(formatFailure(current.error()));
                    default -> throw new RuntimeException("docling returned unknown job status: " + current.status());
                }
            } catch (TransientDoclingException e) {
                sleep(pollInterval, deadlineNanos);
            }
        }
    }

    private JobEnvelope submitUntilAccepted(ParseRequest request, long deadlineNanos) {
        final String body;
        try {
            body = OBJECT_MAPPER.writeValueAsString(request);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize docling request", e);
        }

        while (true) {
            ensureWithinDeadline(deadlineNanos);
            try {
                HttpResponse<String> response = send(buildRequest("POST", "/v1/jobs", body));
                if (response.statusCode() == 429) {
                    sleep(retryAfter(response), deadlineNanos);
                    continue;
                }
                requireSuccess(response, "submit");
                return readJob(response.body());
            } catch (TransientDoclingException e) {
                sleep(pollInterval, deadlineNanos);
            }
        }
    }

    private HttpRequest buildRequest(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", authorization)
                .timeout(REQUEST_TIMEOUT);
        if (body == null) {
            return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
        }
        return builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("docling request interrupted", e);
        } catch (IOException e) {
            throw new TransientDoclingException("docling transport failure", e);
        }
    }

    private void acknowledge(String jobId) {
        try {
            send(buildRequest("DELETE", "/v1/jobs/" + jobId, null));
        } catch (RuntimeException ignored) {
            // Best effort: Docling also removes unacknowledged results by TTL.
        }
    }

    private void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        if (response.statusCode() == 401) {
            throw new RuntimeException("docling authentication failed; check APP_DOCLING_API_TOKEN");
        }
        throw new RuntimeException(
                "docling " + operation + " HTTP " + response.statusCode() + ": "
                        + safeTruncate(response.body()));
    }

    private JobEnvelope readJob(String body) {
        try {
            return OBJECT_MAPPER.readValue(body, JobEnvelope.class);
        } catch (Exception e) {
            throw new RuntimeException("failed to decode docling job response", e);
        }
    }

    private Duration retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("5");
        try {
            long seconds = Math.max(1, Math.min(30, Long.parseLong(value)));
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            return pollInterval;
        }
    }

    private void sleep(Duration duration, long deadlineNanos) {
        ensureWithinDeadline(deadlineNanos);
        long remainingNanos = deadlineNanos - System.nanoTime();
        long sleepNanos = Math.min(duration.toNanos(), remainingNanos);
        if (sleepNanos <= 0) {
            throw timeout();
        }
        try {
            long millis = Math.max(1, Duration.ofNanos(sleepNanos).toMillis());
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("docling polling interrupted", e);
        }
    }

    private void ensureWithinDeadline(long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) {
            throw timeout();
        }
    }

    private RuntimeException timeout() {
        return new RuntimeException("docling job did not finish within " + maxWait);
    }

    private String formatFailure(JobError error) {
        if (error == null) {
            return "docling job failed";
        }
        return "docling job failed [" + error.code() + "]: " + safeTruncate(error.message());
    }

    private String safeTruncate(String text) {
        return text != null && text.length() > 300 ? text.substring(0, 300) : text;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobEnvelope(
            String jobId,
            String requestId,
            String status,
            ParseResponse result,
            JobError error
    ) {
        private String normalizedStatus() {
            return status == null ? "" : status.toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobError(String code, String message) {
    }

    private static class TransientDoclingException extends RuntimeException {
        private TransientDoclingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
