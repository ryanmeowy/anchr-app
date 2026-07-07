package com.anchr.core.integration.ai.adapter;

import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.port.EmbeddingProfileProvider;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.model.EmbedParamEnum;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityEmbeddingProfileProvider implements EmbeddingProfileProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final CapabilityResolver configResolver;

    @Override
    public Optional<EmbeddingProfile> getActiveEmbeddingProfile() {
        return configResolver.activeForSlot(CapabilityResolver.SLOT_EMBEDDING)
                .flatMap(CapabilityEmbeddingProfileProvider::createProfile);
    }

    static Optional<EmbeddingProfile> createProfile(CapabilityConfig config) {
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
            return Optional.of(new EmbeddingProfile(
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
        return OBJECT_MAPPER.readValue(config.getExtraConfig(), new TypeReference<>() {});
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
