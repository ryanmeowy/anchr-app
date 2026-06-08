package com.anchr.core.settings.application.impl;

import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.config.EmbeddingProperties;
import com.anchr.core.settings.config.CapabilityProviderProperties;
import com.anchr.core.settings.domain.model.ProviderType;
import com.anchr.core.settings.domain.repository.AppSettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Resolves runtime provider selection from persisted settings with startup defaults.
 */
@Service
@RequiredArgsConstructor
public class ProviderSelectionService {

    public static final String SETTING_KEY = "provider.selection";

    private final AppSettingRepository appSettingRepository;
    private final ObjectMapper objectMapper;
    private final CapabilityProviderProperties capabilityProviderProperties;
    private final EmbeddingProperties embeddingProperties;

    public String resolve(ProviderType providerType) {
        String workspaceId = UserContextHolder.get().workspaceId();
        return appSettingRepository.find(workspaceId, SETTING_KEY)
                .map(setting -> readProviderName(setting.getSettingValue(), providerType))
                .filter(StringUtils::hasText)
                .orElseGet(() -> defaultProvider(providerType));
    }

    public String defaultProvider(ProviderType providerType) {
        return switch (providerType) {
            case GENERATION -> capabilityProviderProperties.getGen();
            case EMBEDDING -> embeddingProperties.getBackend();
            case RERANK -> capabilityProviderProperties.getRerank();
            case OCR -> capabilityProviderProperties.getOcr();
            case OBJECT_STORAGE -> capabilityProviderProperties.getObjectStorage();
            case WEB_SEARCH -> "";
        };
    }

    private String readProviderName(String json, ProviderType providerType) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode value = node.get(providerType.name().toLowerCase(Locale.ROOT));
            return value == null || value.isNull() ? "" : value.asText();
        } catch (Exception e) {
            return "";
        }
    }
}
