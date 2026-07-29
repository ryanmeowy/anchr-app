package com.anchr.core.settings.application.support;

import com.anchr.core.settings.application.model.CapabilityEmbeddingProfileSnapshot;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.model.EmbedParamEnum;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/** Builds Capability-owned embedding snapshots without exposing provider credentials. */
@Slf4j
public final class CapabilityEmbeddingProfileFactory {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private CapabilityEmbeddingProfileFactory() {
    }

    public static Optional<CapabilityEmbeddingProfileSnapshot> create(CapabilityConfig config) {
        if (config == null || !StringUtils.hasText(config.getModelName())) {
            return Optional.empty();
        }
        try {
            Map<String, Object> extraConfig = parseExtraConfig(config);
            Integer dimension = extractDimension(extraConfig);
            if (dimension == null || dimension <= 0) {
                return Optional.empty();
            }
            String canonicalProfileConfig = OBJECT_MAPPER.writeValueAsString(
                    Map.of(EmbedParamEnum.DIMENSIONS.getKey(),
                            extraConfig.get(EmbedParamEnum.DIMENSIONS.getKey())));
            String fingerprint = fingerprint(
                    normalize(config.getCapability()),
                    normalizeBaseUrl(config.getBaseUrl()),
                    normalize(config.getModelName()),
                    canonicalProfileConfig);
            return Optional.of(new CapabilityEmbeddingProfileSnapshot(
                    config.getId(),
                    config.getCapability(),
                    config.getModelName(),
                    dimension,
                    fingerprint));
        } catch (Exception e) {
            log.warn("Failed to build embedding profile for config {}: {}",
                    config.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private static Map<String, Object> parseExtraConfig(CapabilityConfig config) throws Exception {
        if (!StringUtils.hasText(config.getExtraConfig())) {
            return Map.of();
        }
        return OBJECT_MAPPER.readValue(config.getExtraConfig(), new TypeReference<>() {
        });
    }

    private static Integer extractDimension(Map<String, Object> extraConfig) {
        Object dimension = extraConfig.get(EmbedParamEnum.DIMENSIONS.getKey());
        if (dimension == null || !StringUtils.hasText(dimension.toString())) {
            return null;
        }
        return Integer.valueOf(dimension.toString());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = normalize(value);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
