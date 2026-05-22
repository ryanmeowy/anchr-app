package com.anchr.core.settings.application.impl;

import com.anchr.core.common.config.EmbeddingProperties;
import com.anchr.core.integration.constant.AliyunConstant;
import com.anchr.core.integration.storage.service.aliyun.config.AliyunObjectStorageConfig;
import com.anchr.core.settings.application.SettingsQueryService;
import com.anchr.core.settings.application.provider.ProviderIdentity;
import com.anchr.core.settings.domain.model.ProviderType;
import com.anchr.core.settings.interfaces.rest.dto.CapabilitiesDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityDTO;
import com.anchr.core.settings.interfaces.rest.dto.ProviderDTO;
import com.anchr.core.settings.interfaces.rest.dto.ProviderListDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Settings overview query service.
 */
@Service
@RequiredArgsConstructor
public class SettingsQueryServiceImpl implements SettingsQueryService {

    private final ProviderSelectionService providerSelectionService;
    private final ProviderRuntimeRegistry providerRuntimeRegistry;
    private final EmbeddingProperties embeddingProperties;
    private final AliyunObjectStorageConfig aliyunObjectStorageConfig;

    @Override
    public CapabilitiesDTO capabilities() {
        return CapabilitiesDTO.builder()
                .generation(capability(ProviderType.GENERATION, null, null))
                .embedding(capability(ProviderType.EMBEDDING, embeddingProperties.getModel(), embeddingProperties.getDimension()))
                .rerank(capability(ProviderType.RERANK, "gte-rerank-v2", null))
                .ocr(capability(ProviderType.OCR, null, null))
                .objectStorage(capability(ProviderType.OBJECT_STORAGE, null, null))
                .webSearch(CapabilityDTO.builder()
                        .enabled(false)
                        .provider("")
                        .reason("Provider not configured.")
                        .build())
                .build();
    }

    @Override
    public ProviderListDTO providers() {
        List<ProviderDTO> providers = new ArrayList<>();
        for (ProviderIdentity provider : providerRuntimeRegistry.list()) {
            providers.add(toProvider(provider.providerType(), provider.providerName()));
        }
        addDefaultIfMissing(providers, ProviderType.GENERATION);
        addDefaultIfMissing(providers, ProviderType.EMBEDDING);
        addDefaultIfMissing(providers, ProviderType.RERANK);
        addDefaultIfMissing(providers, ProviderType.OCR);
        addDefaultIfMissing(providers, ProviderType.OBJECT_STORAGE);
        return ProviderListDTO.builder()
                .providers(providers.stream()
                        .sorted(Comparator.comparing(ProviderDTO::getProviderType)
                                .thenComparing(ProviderDTO::getProviderName))
                        .toList())
                .build();
    }

    private CapabilityDTO capability(ProviderType providerType, String model, Integer dimension) {
        String providerName = providerSelectionService.resolve(providerType);
        boolean available = providerRuntimeRegistry.available(providerType, providerName);
        return CapabilityDTO.builder()
                .enabled(available)
                .provider(providerName)
                .model(model)
                .dimension(dimension)
                .reason(available ? null : "Provider not available in current process.")
                .build();
    }

    private void addDefaultIfMissing(List<ProviderDTO> providers, ProviderType providerType) {
        String providerName = providerSelectionService.defaultProvider(providerType);
        if (!StringUtils.hasText(providerName)) {
            return;
        }
        Set<String> keys = providers.stream()
                .map(provider -> provider.getProviderType() + ":" + provider.getProviderName())
                .collect(Collectors.toSet());
        String key = providerType.name() + ":" + providerName;
        if (!keys.contains(key)) {
            providers.add(toProvider(providerType, providerName));
        }
    }

    private ProviderDTO toProvider(ProviderType providerType, String providerName) {
        String selected = providerSelectionService.resolve(providerType);
        boolean available = providerRuntimeRegistry.available(providerType, providerName);
        return ProviderDTO.builder()
                .providerType(providerType.name())
                .providerName(providerName)
                .enabled(providerName.equals(selected))
                .available(available)
                .hotSwitchable(available)
                .secretConfigured(secretConfigured(providerType, providerName))
                .maskedApiKey(maskedSecret(providerType, providerName))
                .effectiveStrategy(effectiveStrategy(providerType))
                .warnings(available ? List.of() : List.of("Provider is not loaded by current application process."))
                .build();
    }

    private boolean secretConfigured(ProviderType providerType, String providerName) {
        if ("aliyun".equals(providerName) && providerType == ProviderType.OBJECT_STORAGE) {
            return StringUtils.hasText(aliyunObjectStorageConfig.getAccessKeyId())
                    && StringUtils.hasText(aliyunObjectStorageConfig.getAccessKeySecret());
        }
        if ("aliyun".equals(providerName)) {
            return StringUtils.hasText(System.getenv(AliyunConstant.BAILIAN_API_KEY_ENV_NAME));
        }
        return false;
    }

    private String maskedSecret(ProviderType providerType, String providerName) {
        if (!secretConfigured(providerType, providerName)) {
            return "";
        }
        return "configured";
    }

    private String effectiveStrategy(ProviderType providerType) {
        return switch (providerType) {
            case EMBEDDING -> "Hot switch affects new embedding calls; existing documents require reembed.";
            case OBJECT_STORAGE -> "Hot switch affects new storage operations only.";
            case WEB_SEARCH -> "Not configured.";
            default -> "Hot switch takes effect for new requests.";
        };
    }
}
