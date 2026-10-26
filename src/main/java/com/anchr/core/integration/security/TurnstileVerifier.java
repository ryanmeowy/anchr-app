package com.anchr.core.integration.security;

import com.anchr.core.common.config.DemoProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Server-side validation of Cloudflare Turnstile tokens.
 *
 * <p>Turnstile tokens are single use and expire after roughly five minutes, so every call has to
 * be forwarded to Siteverify as-is; results are never cached locally.
 *
 * <p>Validation fails closed: if Cloudflare cannot be reached the caller must reject the request
 * rather than let unverified traffic through. A timeout keeps that rejection fast.
 */
@Slf4j
@Component
public class TurnstileVerifier {

    private static final String VERIFY_ENDPOINT =
            "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DemoProperties demoProperties;

    /**
     * Only tests reassign these; production always uses the Cloudflare endpoint with the shared
     * client. Kept as fields rather than constructor arguments so the bean keeps a single
     * construction path (enforced by {@code SpringBeanConstructorContractTest}).
     */
    HttpClient httpClient = HTTP_CLIENT;
    String verifyEndpoint = VERIFY_ENDPOINT;

    public TurnstileVerifier(DemoProperties demoProperties) {
        this.demoProperties = demoProperties;
    }

    /**
     * @return true only when Cloudflare explicitly accepted the token
     */
    public boolean verify(String token) {
        if (!demoProperties.turnstileVerificationActive()) {
            log.warn("Turnstile verification is DISABLED; not for production use.");
            return true;
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        if (!demoProperties.hasTurnstileSecret()) {
            log.error("Turnstile secret is missing while verification is active.");
            return false;
        }
        String form = "secret=" + encode(demoProperties.turnstileSecretKey())
                + "&response=" + encode(token);
        HttpRequest request = HttpRequest.newBuilder(URI.create(verifyEndpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("Turnstile verification returned HTTP {}", response.statusCode());
                return false;
            }
            JsonNode body = OBJECT_MAPPER.readTree(response.body());
            boolean success = body.path("success").asBoolean(false);
            if (!success) {
                log.info("Turnstile rejected the token, errorCodes={}",
                        body.path("error-codes"));
            }
            return success;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Turnstile verification interrupted");
            return false;
        } catch (Exception exception) {
            log.warn("Turnstile verification failed: {}", exception.getMessage());
            return false;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
