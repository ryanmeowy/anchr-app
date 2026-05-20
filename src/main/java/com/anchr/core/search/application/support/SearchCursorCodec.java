package com.anchr.core.search.application.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.search.config.AppSearchProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Opaque cursor codec with signature verification.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchCursorCodec {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final AppSearchProperties appSearchProperties;

    @Value("${app.security.admin-secret:}")
    private String adminSecret;

    private byte[] signingKey;

    @PostConstruct
    public void init() {
        String configuredCursorSecret = appSearchProperties.getPage().getCursorSecret();
        String secret = StringUtils.hasText(configuredCursorSecret) ? configuredCursorSecret : adminSecret;
        if (!StringUtils.hasText(secret)) {
            byte[] randomKey = new byte[32];
            new SecureRandom().nextBytes(randomKey);
            this.signingKey = randomKey;
            log.warn("Cursor signing secret is not configured; using ephemeral in-memory key.");
            return;
        }
        this.signingKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String encode(SearchCursorPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Cursor payload cannot be null.");
        }
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String payloadB64 = URL_ENCODER.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signatureB64 = URL_ENCODER.encodeToString(sign(payloadB64));
            return payloadB64 + "." + signatureB64;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode cursor.", e);
        }
    }

    public SearchCursorPayload decode(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            throw new IllegalArgumentException("Cursor is required.");
        }
        String[] parts = cursor.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Cursor is invalid.");
        }
        String payloadB64 = parts[0];
        String signatureB64 = parts[1];

        byte[] expectedSign = sign(payloadB64);
        byte[] providedSign;
        try {
            providedSign = URL_DECODER.decode(signatureB64);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cursor is invalid.");
        }
        if (!MessageDigest.isEqual(expectedSign, providedSign)) {
            throw new IllegalArgumentException("Cursor signature mismatch.");
        }

        try {
            byte[] payloadBytes = URL_DECODER.decode(payloadB64);
            SearchCursorPayload payload = objectMapper.readValue(payloadBytes, SearchCursorPayload.class);
            if (!StringUtils.hasText(payload.getSessionId())) {
                throw new IllegalArgumentException("Cursor session id is missing.");
            }
            if (!StringUtils.hasText(payload.getQueryFingerprint())) {
                throw new IllegalArgumentException("Cursor fingerprint is missing.");
            }
            if (payload.getOffset() < 0) {
                throw new IllegalArgumentException("Cursor offset is invalid.");
            }
            return payload;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cursor payload is invalid.");
        }
    }

    private byte[] sign(String payloadB64) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(signingKey, HMAC_SHA_256));
            return mac.doFinal(payloadB64.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign cursor payload.", e);
        }
    }
}
