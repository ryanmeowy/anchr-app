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
import java.util.Objects;
import java.util.function.Consumer;

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
        return parse(request, ignored -> { });
    }

    /**
     * Compatibility facade for the current ingestion processor. ANCHR-106 will move polling out
     * of this method and call submitJob/getJob/ackJob from its persisted stage scheduler.
     */
    public ParseResponse parse(ParseRequest request, Consumer<DoclingJob> acceptedJobConsumer) {
        Objects.requireNonNull(acceptedJobConsumer, "acceptedJobConsumer");
        long deadlineNanos = System.nanoTime() + maxWait.toNanos();
        int resubmits = 0;
        DoclingJob job = submitUntilAccepted(request, deadlineNanos);
        acceptedJobConsumer.accept(job);

        while (true) {
            ensureWithinDeadline(deadlineNanos);
            try {
                DoclingJob current = getJob(job.jobId());
                switch (current.normalizedStatus()) {
                    case "queued", "running" -> sleep(pollInterval, deadlineNanos);
                    case "succeeded" -> {
                        if (current.result() == null) {
                            throw new RuntimeException("docling succeeded without a result");
                        }
                        acknowledgeBestEffort(current.jobId());
                        return current.result();
                    }
                    case "failed" -> throw new RuntimeException(formatFailure(current.error()));
                    default -> throw new RuntimeException("docling returned unknown job status: " + current.status());
                }
            } catch (DoclingClientException e) {
                if (e.kind() == FailureKind.NOT_FOUND && resubmits < maxResubmits) {
                    resubmits++;
                    job = submitUntilAccepted(request, deadlineNanos);
                    acceptedJobConsumer.accept(job);
                } else if (e.kind() == FailureKind.TRANSIENT) {
                    sleep(e.retryAfter() == null ? pollInterval : e.retryAfter(), deadlineNanos);
                } else {
                    throw e;
                }
            }
        }
    }

    public DoclingJob submitJob(ParseRequest request) {
        final String body;
        try {
            body = OBJECT_MAPPER.writeValueAsString(request);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize docling request", e);
        }
        HttpResponse<String> response = send(buildRequest("POST", "/v1/jobs", body));
        requireSuccess(response, "submit");
        DoclingJob job = readJob(response.body());
        requireJobIdentity(job, null, request.requestId(), response.statusCode(), "submit");
        return job;
    }

    public DoclingJob getJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        HttpResponse<String> response = send(buildRequest("GET", "/v1/jobs/" + jobId, null));
        requireSuccess(response, "get");
        DoclingJob job = readJob(response.body());
        requireJobIdentity(job, jobId, null, response.statusCode(), "get");
        return job;
    }

    /** DELETE is idempotent: an already acknowledged or expired job is considered acknowledged. */
    public void ackJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        HttpResponse<String> response = send(buildRequest("DELETE", "/v1/jobs/" + jobId, null));
        if (response.statusCode() == 404) {
            return;
        }
        requireSuccess(response, "ack");
    }

    private DoclingJob submitUntilAccepted(ParseRequest request, long deadlineNanos) {

        while (true) {
            ensureWithinDeadline(deadlineNanos);
            try {
                return submitJob(request);
            } catch (DoclingClientException e) {
                if (e.kind() != FailureKind.TRANSIENT) {
                    throw e;
                }
                sleep(e.retryAfter() == null ? pollInterval : e.retryAfter(), deadlineNanos);
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
            throw new DoclingClientException(
                    FailureKind.TRANSIENT, null, null, "docling transport failure", e);
        }
    }

    private void acknowledgeBestEffort(String jobId) {
        try {
            ackJob(jobId);
        } catch (RuntimeException ignored) {
            // Best effort: Docling also removes unacknowledged results by TTL.
        }
    }

    private void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        int statusCode = response.statusCode();
        FailureKind kind;
        if (statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500) {
            kind = FailureKind.TRANSIENT;
        } else if (statusCode == 404) {
            kind = FailureKind.NOT_FOUND;
        } else if (statusCode == 409) {
            kind = FailureKind.CONFLICT;
        } else if (statusCode == 401) {
            kind = FailureKind.CONFIGURATION;
        } else {
            kind = FailureKind.PERMANENT;
        }
        Duration retryAfter = kind == FailureKind.TRANSIENT ? retryAfter(response) : null;
        String message = kind == FailureKind.CONFIGURATION
                ? "docling authentication failed; check APP_DOCLING_API_TOKEN"
                : "docling " + operation + " HTTP " + statusCode + ": " + safeTruncate(response.body());
        throw new DoclingClientException(kind, statusCode, retryAfter, message, null);
    }

    private DoclingJob readJob(String body) {
        try {
            return OBJECT_MAPPER.readValue(body, DoclingJob.class);
        } catch (Exception e) {
            throw new DoclingClientException(
                    FailureKind.PERMANENT,
                    null,
                    null,
                    "failed to decode docling job response",
                    e);
        }
    }

    private void requireJobIdentity(DoclingJob job, String expectedJobId, String expectedRequestId,
                                    int statusCode, String operation) {
        boolean malformed = job == null
                || job.jobId() == null || job.jobId().isBlank()
                || job.requestId() == null || job.requestId().isBlank()
                || job.status() == null || job.status().isBlank();
        if (malformed) {
            throw new DoclingClientException(
                    FailureKind.PERMANENT,
                    statusCode,
                    null,
                    "docling " + operation + " returned a malformed job identity",
                    null);
        }
        boolean mismatched = (expectedJobId != null && !Objects.equals(expectedJobId, job.jobId()))
                || (expectedRequestId != null && !Objects.equals(expectedRequestId, job.requestId()));
        if (mismatched) {
            throw new DoclingClientException(
                    FailureKind.PERMANENT,
                    statusCode,
                    null,
                    "docling " + operation + " returned a mismatched job identity",
                    null);
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

    private String formatFailure(DoclingJobError error) {
        if (error == null) {
            return "docling job failed";
        }
        return "docling job failed [" + error.code() + "]: " + safeTruncate(error.message());
    }

    private String safeTruncate(String text) {
        return text != null && text.length() > 300 ? text.substring(0, 300) : text;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DoclingJob(
            String jobId,
            String requestId,
            String status,
            ParseResponse result,
            DoclingJobError error
    ) {
        public String normalizedStatus() {
            return status == null ? "" : status.toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DoclingJobError(String code, String message) {
    }

    public enum FailureKind {
        TRANSIENT,
        NOT_FOUND,
        CONFLICT,
        CONFIGURATION,
        PERMANENT
    }

    public static class DoclingClientException extends RuntimeException {
        private final FailureKind kind;
        private final Integer statusCode;
        private final Duration retryAfter;

        private DoclingClientException(FailureKind kind, Integer statusCode, Duration retryAfter,
                                       String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
            this.statusCode = statusCode;
            this.retryAfter = retryAfter;
        }

        public FailureKind kind() {
            return kind;
        }

        public Integer statusCode() {
            return statusCode;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }
}
