package com.anchr.core.settings.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.settings.application.ProviderSettingService;
import com.anchr.core.settings.application.model.ProviderSwitchResult;
import com.anchr.core.settings.domain.model.ProviderSetting;
import com.anchr.core.settings.domain.model.ProviderType;
import com.anchr.core.settings.domain.repository.AppSettingRepository;
import com.anchr.core.settings.domain.repository.ProviderConfigVersionRepository;
import com.anchr.core.settings.domain.repository.ProviderSettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime provider setting service.
 */
@Service
@RequiredArgsConstructor
public class ProviderSettingServiceImpl implements ProviderSettingService {

    private final AppSettingRepository appSettingRepository;
    private final ProviderSettingRepository providerSettingRepository;
    private final ProviderConfigVersionRepository providerConfigVersionRepository;
    private final ProviderRuntimeRegistry providerRuntimeRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public ProviderSwitchResult switchProvider(ProviderType providerType, String providerName) {
        if (!StringUtils.hasText(providerName)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "providerName cannot be blank.");
        }
        String normalizedProviderName = providerName.trim().toLowerCase(Locale.ROOT);
        if (!providerRuntimeRegistry.available(providerType, normalizedProviderName)) {
            throw new BusinessException(ApiError.PROVIDER_UNAVAILABLE,
                    "Provider is not available in current process: " + providerType.name() + "/" + normalizedProviderName);
        }
        RequestUserContext context = UserContextHolder.get();
        ProviderSetting setting = providerSettingRepository.upsert(
                context.workspaceId(), providerType, normalizedProviderName, "{}", null, true, context.userId());
        providerConfigVersionRepository.save(setting.getId(), setting.getVersion(), configSnapshot(providerType,
                normalizedProviderName), context.userId());
        appSettingRepository.upsert(context.workspaceId(), ProviderSelectionService.SETTING_KEY,
                nextSelectionJson(context.workspaceId(), providerType, normalizedProviderName), context.userId());
        return ProviderSwitchResult.builder()
                .providerType(providerType)
                .providerName(normalizedProviderName)
                .version(setting.getVersion())
                .effectiveImmediately(true)
                .warnings(warnings(providerType))
                .build();
    }

    private String nextSelectionJson(String workspaceId, ProviderType providerType, String providerName) {
        ObjectNode root = objectMapper.createObjectNode();
        appSettingRepository.find(workspaceId, ProviderSelectionService.SETTING_KEY).ifPresent(setting -> {
            try {
                JsonNode node = objectMapper.readTree(setting.getSettingValue());
                if (node instanceof ObjectNode objectNode) {
                    root.setAll(objectNode);
                }
            } catch (Exception ignored) {
                root.removeAll();
            }
        });
        root.put(providerType.name().toLowerCase(Locale.ROOT), providerName);
        return root.toString();
    }

    private String configSnapshot(ProviderType providerType, String providerName) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "providerType", providerType.name(),
                    "providerName", providerName
            ));
        } catch (Exception e) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Failed to serialize provider config snapshot.", e);
        }
    }

    private List<String> warnings(ProviderType providerType) {
        return switch (providerType) {
            case EMBEDDING -> List.of("Embedding provider changes require reembed for existing documents.");
            case OBJECT_STORAGE -> List.of("Existing object keys remain on the previous storage provider.");
            case WEB_SEARCH -> List.of("Web search provider is not configured in this phase.");
            default -> List.of();
        };
    }
}
