package com.anchr.core.integration.ai.client;

import com.anchr.core.integration.ai.ParseRequest;
import com.anchr.core.integration.ai.ParseResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client for anchr-docling sidecar.
 */
public class DoclingClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;

    public DoclingClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    public ParseResponse parse(ParseRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/parse"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            json.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("docling HTTP " + response.statusCode()
                        + ": " + safeTruncate(response.body()));
            }

            return objectMapper.readValue(response.body(), ParseResponse.class);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("docling request failed: " + e.getMessage(), e);
        }
    }

    private String safeTruncate(String text) {
        return text != null && text.length() > 200 ? text.substring(0, 200) : text;
    }
}
