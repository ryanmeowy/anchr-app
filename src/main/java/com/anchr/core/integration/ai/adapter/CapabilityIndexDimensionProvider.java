package com.anchr.core.integration.ai.adapter;

import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.search.domain.port.IndexDimensionProvider;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.model.EmbedParamEnum;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityIndexDimensionProvider implements IndexDimensionProvider {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final CapabilityResolver configResolver;

    @Override
    public Optional<Integer> getActiveEmbeddingDimension() {
        return configResolver.activeForSlot(CapabilityResolver.SLOT_EMBEDDING)
                .flatMap(CapabilityIndexDimensionProvider::extractDimension);
    }

    @Override
    public Optional<String> getActiveEmbeddingModelKey() {
        return configResolver.activeForSlot(CapabilityResolver.SLOT_EMBEDDING)
                .map(CapabilityIndexDimensionProvider::buildModelKey);
    }

    private static String buildModelKey(CapabilityConfig config) {
        return (config.getBaseUrl() != null ? config.getBaseUrl() : "") + "|"
                + (config.getModelName() != null ? config.getModelName() : "");
    }

    public static Optional<Integer> extractDimension(CapabilityConfig config) {
        if (!StringUtils.hasText(config.getExtraConfig())) {
            return Optional.empty();
        }
        try {
            Map<String, Object> extraMap = objectMapper.readValue(
                    config.getExtraConfig(), new TypeReference<>() {});
           Object dimObj = extraMap.get(EmbedParamEnum.DIMENSIONS.getKey());
            if (dimObj != null && StringUtils.hasText(dimObj.toString())) {
                return Optional.of(Integer.valueOf(dimObj.toString()));
            }
        } catch (Exception e) {
            log.warn("Failed to parse extraConfig for dimension: {}", e.getMessage());
        }
        return Optional.empty();
    }
}
