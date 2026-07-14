package com.anchr.core.integration.ai.client;

import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.common.model.ParseResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void parseShouldAuthenticatePollAndAcknowledge() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && "/v1/jobs".equals(path)) {
                respond(exchange, 202, jobJson("queued", "null", "null"));
            } else if ("GET".equals(exchange.getRequestMethod())) {
                if (polls.getAndIncrement() == 0) {
                    respond(exchange, 200, jobJson("running", "null", "null"));
                } else {
                    respond(exchange, 200, jobJson("succeeded", resultJson(), "null"));
                }
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                deletes.incrementAndGet();
                respond(exchange, 204, "");
            } else {
                respond(exchange, 404, "{}");
            }
        });

        ParseResponse response = client(Duration.ofSeconds(2)).parse(request());

        assertEquals("Bearer " + TOKEN, authorization.get());
        assertEquals("parsed", response.text());
        assertEquals(1, deletes.get());
    }

    @Test
    void parseShouldResubmitAfterJobIsLost() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger polls = new AtomicInteger();
        server = startServer(exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                submissions.incrementAndGet();
                respond(exchange, 202, jobJson("queued", "null", "null"));
            } else if ("GET".equals(exchange.getRequestMethod()) && polls.getAndIncrement() == 0) {
                respond(exchange, 404, "{}");
            } else if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, jobJson("succeeded", resultJson(), "null"));
            } else {
                respond(exchange, 204, "");
            }
        });

        ParseResponse response = client(Duration.ofSeconds(2)).parse(request());

        assertEquals("parsed", response.text());
        assertEquals(2, submissions.get());
    }

    @Test
    void parseShouldSurfaceFailedJob() throws Exception {
        server = startServer(exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 202, jobJson("queued", "null", "null"));
            } else {
                respond(exchange, 200, jobJson(
                        "failed",
                        "null",
                        "{\"code\":\"QUEUE_TIMEOUT\",\"message\":\"queue expired\"}"));
            }
        });

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> client(Duration.ofSeconds(2)).parse(request()));

        assertTrue(error.getMessage().contains("QUEUE_TIMEOUT"));
    }

    private DoclingClient client(Duration maxWait) {
        return new DoclingClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                TOKEN,
                Duration.ofMillis(5),
                maxWait,
                2);
    }

    private ParseRequest request() {
        return new ParseRequest(
                "task-1:item-1",
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
        return "{\"jobId\":\"job-1\",\"requestId\":\"task-1:item-1\",\"status\":\""
                + status + "\",\"result\":" + result + ",\"error\":" + error + "}";
    }

    private static String resultJson() {
        return "{\"requestId\":\"task-1:item-1\",\"parser\":\"docling\","
                + "\"format\":\"chunks\",\"text\":\"parsed\",\"fileType\":\"pdf\","
                + "\"pages\":[],\"chunks\":[],\"images\":[],\"warnings\":[]}";
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
