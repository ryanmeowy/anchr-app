package com.anchr.core.integration.ai;

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
//            ParseRequest request = new ParseRequest(
//                    null, sourceUrl, fileName, null, ParseRequest.Options.chunkModel(), oss);
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


    public static void main(String[] args) {
        DoclingClient client = new DoclingClient("http://127.0.0.1:8091");
        String url = "https://arg-image.oss-cn-shanghai.aliyuncs.com/uploads/anchr/01.%E5%9F%BA%E7%A1%80%E6%9E%B6%E6%9E%84%EF%BC%9A%E4%B8%80%E6%9D%A1SQL%E6%9F%A5%E8%AF%A2%E8%AF%AD%E5%8F%A5%E6%98%AF%E5%A6%82%E4%BD%95%E6%89%A7%E8%A1%8C%E7%9A%84%EF%BC%9F.pdf";
        ParseRequest req = new ParseRequest(null, url, "01.基础架构.pdf", null, ParseRequest.Options.chunkModel(), null);
        ParseResponse parse = client.parse(req);
        System.err.println(parse);
    }
}
