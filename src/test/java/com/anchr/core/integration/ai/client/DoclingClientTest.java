package com.anchr.core.integration.ai.client;

import com.anchr.core.common.model.ParseRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoclingClientTest {

    private static final String TOKEN = "a".repeat(64);
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void submitJobShouldAuthenticateSerializeRequestAndValidateIdentity() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> submittedBody = new AtomicReference<>();
        server = startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            submittedBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            respond(exchange, 202, jobJson("queued", "null", "null"));
        });

        DoclingClient.DoclingJob job = client().submitJob(request());

        assertEquals("Bearer " + TOKEN, authorization.get());
        assertTrue(submittedBody.get().contains("\"includeEmbeddedImages\":false"));
        assertFalse(submittedBody.get().contains("\"oss\""));
        assertEquals("job-1", job.jobId());
        assertEquals("task-1:item-1:1", job.requestId());
    }

    @Test
    void getJobShouldValidateEnvelopeAndSucceededResultIdentity() throws Exception {
        AtomicInteger gets = new AtomicInteger();
        server = startServer(exchange -> {
            gets.incrementAndGet();
            respond(exchange, 200, jobJson("succeeded", resultJson(), "null"));
        });

        DoclingClient.DoclingJob job = client().getJob("job-1", "task-1:item-1:1");

        assertEquals("succeeded", job.normalizedStatus());
        assertEquals("parsed", job.result().text());
        assertEquals(1, gets.get());
    }

    @Test
    void getJobShouldRejectMismatchedRequestIdentity() throws Exception {
        server = startServer(exchange -> respond(exchange, 200,
                "{\"jobId\":\"job-1\",\"requestId\":\"another-request\",\"status\":\"running\"}"));

        DoclingClient.DoclingClientException error = assertThrows(
                DoclingClient.DoclingClientException.class,
                () -> client().getJob("job-1", "task-1:item-1:1"));

        assertEquals(DoclingClient.FailureKind.PERMANENT, error.kind());
        assertTrue(error.getMessage().contains("mismatched job identity"));
    }

    @Test
    void getJobShouldRejectMismatchedSucceededResultIdentity() throws Exception {
        server = startServer(exchange -> respond(exchange, 200,
                jobJson("succeeded", resultJson("another-request"), "null")));

        DoclingClient.DoclingClientException error = assertThrows(
                DoclingClient.DoclingClientException.class,
                () -> client().getJob("job-1", "task-1:item-1:1"));

        assertEquals(DoclingClient.FailureKind.PERMANENT, error.kind());
        assertTrue(error.getMessage().contains("mismatched result identity"));
    }

    @Test
    void getJobShouldRejectSucceededJobWithoutResult() throws Exception {
        server = startServer(exchange ->
                respond(exchange, 200, jobJson("succeeded", "null", "null")));

        DoclingClient.DoclingClientException error = assertThrows(
                DoclingClient.DoclingClientException.class,
                () -> client().getJob("job-1", "task-1:item-1:1"));

        assertEquals(DoclingClient.FailureKind.PERMANENT, error.kind());
        assertTrue(error.getMessage().contains("without a result identity"));
    }

    @Test
    void getJobShouldExposeFailedJobErrorForStageClassification() throws Exception {
        server = startServer(exchange -> respond(exchange, 200, jobJson(
                "failed",
                "null",
                "{\"code\":\"QUEUE_TIMEOUT\",\"message\":\"queue expired\"}")));

        DoclingClient.DoclingJob job =
                client().getJob("job-1", "task-1:item-1:1");

        assertEquals("failed", job.normalizedStatus());
        assertEquals("QUEUE_TIMEOUT", job.error().code());
        assertEquals("queue expired", job.error().message());
    }

    @ParameterizedTest
    @CsvSource({
            "408, TRANSIENT",
            "425, TRANSIENT",
            "429, TRANSIENT",
            "500, TRANSIENT",
            "503, TRANSIENT",
            "404, NOT_FOUND",
            "409, CONFLICT",
            "401, CONFIGURATION",
            "422, PERMANENT"
    })
    void submitJobShouldClassifyHttpFailure(int status, DoclingClient.FailureKind expected)
            throws Exception {
        server = startServer(exchange -> respond(exchange, status, "{\"detail\":\"failure\"}"));

        DoclingClient.DoclingClientException error = assertThrows(
                DoclingClient.DoclingClientException.class,
                () -> client().submitJob(request()));

        assertEquals(expected, error.kind());
        assertEquals(status, error.statusCode());
    }

    @Test
    void transientFailureShouldExposeBoundedRetryAfter() throws Exception {
        server = startServer(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "99");
            respond(exchange, 429, "{\"detail\":\"busy\"}");
        });

        DoclingClient.DoclingClientException error = assertThrows(
                DoclingClient.DoclingClientException.class,
                () -> client().submitJob(request()));

        assertEquals(DoclingClient.FailureKind.TRANSIENT, error.kind());
        assertEquals(Duration.ofSeconds(30), error.retryAfter());
    }

    @Test
    void ackJobShouldBeIdempotentForDeletedOrExpiredJob() throws Exception {
        AtomicInteger deletes = new AtomicInteger();
        server = startServer(exchange -> {
            int attempt = deletes.incrementAndGet();
            respond(exchange, attempt == 1 ? 204 : 404, attempt == 1 ? "" : "{}");
        });

        DoclingClient client = client();
        client.ackJob("job-1");
        client.ackJob("job-1");

        assertEquals(2, deletes.get());
    }

    @Test
    void submitJobShouldRejectMismatchedSuccessfulEnvelope() throws Exception {
        server = startServer(exchange -> respond(exchange, 202,
                "{\"jobId\":\"job-1\",\"requestId\":\"another-request\",\"status\":\"queued\"}"));

        DoclingClient.DoclingClientException error = assertThrows(
                DoclingClient.DoclingClientException.class,
                () -> client().submitJob(request()));

        assertEquals(DoclingClient.FailureKind.PERMANENT, error.kind());
    }

    @Test
    void getJobShouldRejectResponseAboveConfiguredLimit() throws Exception {
        server = startServer(exchange -> respond(exchange, 200,
                "{\"jobId\":\"job-1\",\"requestId\":\"task-1:item-1:1\","
                        + "\"status\":\"running\",\"padding\":\"" + "x".repeat(512) + "\"}"));

        DoclingClient.DoclingClientException error = assertThrows(
                DoclingClient.DoclingClientException.class,
                () -> client().getJob("job-1", "task-1:item-1:1", 128));

        assertEquals(DoclingClient.FailureKind.PERMANENT, error.kind());
        assertTrue(error.getMessage().contains("configured size limit"));
    }

    private DoclingClient client() {
        return new DoclingClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                TOKEN);
    }

    private ParseRequest request() {
        return new ParseRequest(
                "task-1:item-1:1",
                2,
                "v1:" + "a".repeat(64),
                "https://anchr.oss-cn-shanghai.aliyuncs.com/file.pdf",
                "file.pdf",
                ParseRequest.Options.chunkModel(),
                null);
    }

    private HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> handler.handle(exchange));
        httpServer.start();
        return httpServer;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static String jobJson(String status, String result, String error) {
        return "{\"jobId\":\"job-1\",\"requestId\":\"task-1:item-1:1\",\"status\":\""
                + status + "\",\"result\":" + result + ",\"error\":" + error + "}";
    }

    private static String resultJson() {
        return resultJson("task-1:item-1:1");
    }

    private static String resultJson(String requestId) {
        return "{\"requestId\":\"" + requestId + "\",\"parser\":\"docling\","
                + "\"format\":\"chunks\",\"text\":\"parsed\",\"fileType\":\"pdf\","
                + "\"pages\":[],\"chunks\":[],\"images\":[],\"warnings\":[]}";
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
