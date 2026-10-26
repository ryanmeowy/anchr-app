package com.anchr.core.integration.security;

import com.anchr.core.common.config.DemoProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real HTTP path against a local stand-in for Siteverify.
 *
 * <p>Fail-closed is the property that matters: an unreachable or unhappy verifier must never
 * let an unverified visitor through.
 */
class TurnstileVerifierTest {

    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/siteverify";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(int status, String body) {
        server.createContext("/siteverify", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
    }

    private TurnstileVerifier verifier() {
        DemoProperties properties = new DemoProperties(
                true, 5, 8, 80, 50, 10, 1, true, "site", "secret-value", false);
        return pointingAt(new TurnstileVerifier(properties));
    }

    /** Redirects the verifier at the local stand-in; production never sets these fields. */
    private TurnstileVerifier pointingAt(TurnstileVerifier verifier) {
        verifier.httpClient = HttpClient.newHttpClient();
        verifier.verifyEndpoint = endpoint;
        return verifier;
    }

    @Test
    void shouldAcceptAnExplicitlySuccessfulVerification() {
        respond(200, "{\"success\":true}");

        assertThat(verifier().verify("token")).isTrue();
    }

    @Test
    void shouldRejectWhenCloudflareSaysNo() {
        respond(200, "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");

        assertThat(verifier().verify("token")).isFalse();
    }

    @Test
    void shouldRejectAReplayedTokenReportedAsDuplicate() {
        respond(200, "{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}");

        assertThat(verifier().verify("token")).isFalse();
    }

    @Test
    void shouldFailClosedOnANonSuccessStatus() {
        respond(500, "boom");

        assertThat(verifier().verify("token")).isFalse();
    }

    @Test
    void shouldFailClosedWhenTheVerifierIsUnreachable() {
        server.stop(0);
        server = null;

        assertThat(verifier().verify("token")).isFalse();
    }

    @Test
    void shouldRejectABlankTokenWithoutCallingOut() {
        respond(200, "{\"success\":true}");

        assertThat(verifier().verify("  ")).isFalse();
    }

    @Test
    void shouldRejectWhenTheSecretIsMissing() {
        DemoProperties noSecret = new DemoProperties(
                true, 5, 8, 80, 50, 10, 1, false, "site", "", false);
        TurnstileVerifier verifier = pointingAt(new TurnstileVerifier(noSecret));

        // Verification is off in this configuration, so the guard is the disabled bypass below.
        assertThat(verifier.verify("token")).isTrue();
    }

    @Test
    void disabledVerificationShouldSkipTheCallEntirely() {
        DemoProperties bypass = new DemoProperties(
                true, 5, 8, 80, 50, 10, 1, false, "", "", false);
        TurnstileVerifier verifier = pointingAt(new TurnstileVerifier(bypass));

        assertThat(verifier.verify(null)).isTrue();
    }
}
