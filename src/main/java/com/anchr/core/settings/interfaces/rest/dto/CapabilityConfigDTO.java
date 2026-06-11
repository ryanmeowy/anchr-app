package com.anchr.core.settings.interfaces.rest.dto;

import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Capability config response — apiKey is masked.
 */
@Value
@Builder
public class CapabilityConfigDTO {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    String baseUrl;
    String modelName;
    Map<String, Object> extraConfig;
    String apiKeyMasked;
    boolean enabled;

    public static CapabilityConfigDTO from(CapabilityConfig config, String apiKeyMasked) {
        return CapabilityConfigDTO.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .extraConfig(parseExtraConfig(config.getExtraConfig()))
                .apiKeyMasked(apiKeyMasked)
                .enabled(config.isEnabled())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseExtraConfig(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
