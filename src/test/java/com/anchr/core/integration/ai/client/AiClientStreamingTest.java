package com.anchr.core.integration.ai.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AiClientStreamingTest {

    @Test
    void shouldConsumeOpenAiCompatibleSseDeltasAndUsage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> respond(exchange, requestBody));
        server.start();
        try {
            AiClient client = new AiClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
            List<String> deltas = new ArrayList<>();

            AiClient.StreamedChatCompletion result = client.chatCompletionsStream(
                    "test-model",
                    List.of(Map.of("role", "user", "content", "你好")),
                    Map.of("temperature", 0),
                    Duration.ofSeconds(5),
                    deltas::add);

            assertThat(deltas).containsExactly("你", "好");
            assertThat(result.content()).isEqualTo("你好");
            assertThat(result.promptTokens()).isEqualTo(9);
            assertThat(result.completionTokens()).isEqualTo(2);
            assertThat(requestBody.get())
                    .contains("\"stream\":true")
                    .contains("\"include_usage\":true");
        } finally {
            server.stop(0);
        }
    }

    private void respond(HttpExchange exchange, AtomicReference<String> requestBody) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String body = """
                data: {"choices":[{"delta":{"content":"你"}}]}

                data: {"choices":[{"delta":{"content":"好"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":2}}

                data: [DONE]

                """;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
