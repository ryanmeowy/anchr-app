package com.anchr.core.integration.ai.client;

import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.common.model.ParseResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

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
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(5);
    private static final int MAX_ERROR_BODY_BYTES = 4096;
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 268_435_456;

    private final String baseUrl;
    private final String authorization;

    public DoclingClient(
            @Value("${app.docling.base-url}") String baseUrl,
            @Value("${app.docling.api-token}") String apiToken
    ) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("app.docling.base-url must not be blank");
        }
        if (apiToken == null || apiToken.length() < 32) {
            throw new IllegalArgumentException("app.docling.api-token must contain at least 32 characters");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.authorization = "Bearer " + apiToken;
    }

    public DoclingJob submitJob(ParseRequest request) {
        return submitJob(request, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public DoclingJob submitJob(ParseRequest request, int responseLimitBytes) {
        Objects.requireNonNull(request, "request");
        validateResponseLimit(responseLimitBytes);
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw new IllegalArgumentException("request.requestId must not be blank");
        }
        final String body;
        try {
            body = OBJECT_MAPPER.writeValueAsString(request);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize docling request", e);
        }
        HttpResponse<InputStream> response = send(buildRequest("POST", "/v1/jobs", body));
        requireSuccess(response, "submit");
        DoclingJob job = readJob(response, "submit", responseLimitBytes);
        requireJobIdentity(job, null, request.requestId(), response.statusCode(), "submit");
        requireSucceededResultIdentity(job, request.requestId(), response.statusCode(), "submit");
        return job;
    }

    /**
     * Loads one Docling job snapshot and verifies that it belongs to the persisted parse request.
     *
     * <p>This method performs exactly one HTTP request. Polling, retry timing and lost-job
     * resubmission are responsibilities of the durable ingestion stage scheduler.</p>
     */
    public DoclingJob getJob(String jobId, String expectedRequestId) {
        return getJob(jobId, expectedRequestId, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public DoclingJob getJob(
            String jobId, String expectedRequestId, int responseLimitBytes) {
        if (expectedRequestId == null || expectedRequestId.isBlank()) {
            throw new IllegalArgumentException("expectedRequestId must not be blank");
        }
        validateResponseLimit(responseLimitBytes);
        return getJobInternal(jobId, expectedRequestId, responseLimitBytes);
    }

    private DoclingJob getJobInternal(
            String jobId, String expectedRequestId, int responseLimitBytes) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        HttpResponse<InputStream> response = send(buildRequest("GET", "/v1/jobs/" + jobId, null));
        requireSuccess(response, "get");
        DoclingJob job = readJob(response, "get", responseLimitBytes);
        requireJobIdentity(job, jobId, expectedRequestId, response.statusCode(), "get");
        String resultRequestId = expectedRequestId == null ? job.requestId() : expectedRequestId;
        requireSucceededResultIdentity(job, resultRequestId, response.statusCode(), "get");
        return job;
    }

    /** DELETE is idempotent: an already acknowledged or expired job is considered acknowledged. */
    public void ackJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        HttpResponse<InputStream> response = send(buildRequest("DELETE", "/v1/jobs/" + jobId, null));
        if (response.statusCode() == 404) {
            closeBody(response);
            return;
        }
        requireSuccess(response, "ack");
        closeBody(response);
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

    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            // Streaming the response prevents HttpClient from buffering an unbounded
            // succeeded payload before the ingestion size limit can be enforced.
            return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DoclingClientException(
                    FailureKind.TRANSIENT, null, null, "docling request interrupted", e);
        } catch (IOException e) {
            throw new DoclingClientException(
                    FailureKind.TRANSIENT, null, null, "docling transport failure", e);
        }
    }

    private void requireSuccess(HttpResponse<InputStream> response, String operation) {
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
        String errorBody = readErrorBody(response);
        String message = kind == FailureKind.CONFIGURATION
                ? "docling authentication failed; check APP_DOCLING_API_TOKEN"
                : "docling " + operation + " HTTP " + statusCode + ": " + errorBody;
        throw new DoclingClientException(kind, statusCode, retryAfter, message, null);
    }

    private DoclingJob readJob(
            HttpResponse<InputStream> response,
            String operation,
            int responseLimitBytes
    ) {
        byte[] body;
        try (InputStream input = response.body()) {
            body = input.readNBytes(responseLimitBytes + 1);
        } catch (IOException e) {
            throw new DoclingClientException(
                    FailureKind.TRANSIENT,
                    response.statusCode(),
                    null,
                    "failed to read docling " + operation + " response",
                    e);
        }
        if (body.length > responseLimitBytes) {
            throw new DoclingClientException(
                    FailureKind.PERMANENT,
                    response.statusCode(),
                    null,
                    "docling " + operation + " response exceeds the configured size limit",
                    null);
        }
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

    private void validateResponseLimit(int responseLimitBytes) {
        if (responseLimitBytes <= 0 || responseLimitBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "docling response limit must be between 1 and Integer.MAX_VALUE - 1");
        }
    }

    private String readErrorBody(HttpResponse<InputStream> response) {
        try (InputStream input = response.body()) {
            byte[] body = input.readNBytes(MAX_ERROR_BODY_BYTES + 1);
            String decoded = new String(
                    body,
                    0,
                    Math.min(body.length, MAX_ERROR_BODY_BYTES),
                    StandardCharsets.UTF_8);
            return safeTruncate(decoded);
        } catch (IOException e) {
            return "<unreadable response body>";
        }
    }

    private void closeBody(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // ACK response content is not part of the protocol.
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

    private void requireSucceededResultIdentity(DoclingJob job, String expectedRequestId,
                                                int statusCode, String operation) {
        if (!"succeeded".equals(job.normalizedStatus())) {
            return;
        }
        ParseResponse result = job.result();
        boolean malformed = result == null
                || result.requestId() == null
                || result.requestId().isBlank();
        if (malformed) {
            throw new DoclingClientException(
                    FailureKind.PERMANENT,
                    statusCode,
                    null,
                    "docling " + operation + " returned succeeded without a result identity",
                    null);
        }
        if (!Objects.equals(expectedRequestId, result.requestId())) {
            throw new DoclingClientException(
                    FailureKind.PERMANENT,
                    statusCode,
                    null,
                    "docling " + operation + " returned a mismatched result identity",
                    null);
        }
    }

    private Duration retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("5");
        try {
            long seconds = Math.max(1, Math.min(30, Long.parseLong(value)));
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            return DEFAULT_RETRY_AFTER;
        }
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
