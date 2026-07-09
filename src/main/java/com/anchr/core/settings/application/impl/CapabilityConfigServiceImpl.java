package com.anchr.core.settings.application.impl;

import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.integration.ai.client.CapabilityClientFactory;
import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.integration.ai.client.GenerationClient;
import com.anchr.core.integration.ai.client.MultiEmbeddingClient;
import com.anchr.core.integration.ai.client.RerankClient;
import com.anchr.core.integration.ai.client.TextEmbeddingClient;
import com.anchr.core.settings.application.CapabilityConfigService;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.model.EmbedParamEnum;
import com.anchr.core.settings.domain.model.ModelTypeEnum;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigUpdateRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConnectionTestRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConnectionTestResultDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default implementation for capability configuration.
 */
@Service
@RequiredArgsConstructor
public class CapabilityConfigServiceImpl implements CapabilityConfigService {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final CapabilityConfigRepository repository;
    private final AesUtil aesUtil;
    private final IdGen idGen;
    private final CapabilityClientFactory clientFactory;
    private final CapabilityResolver configResolver;
    private final ClientCacheManager clientCacheManager;

    @Override
    public List<CapabilityConfigDTO> get(String capability) {
        return repository.findByCapability(capability).stream()
                .map(config -> CapabilityConfigDTO.from(config, maskApiKey(config.getApiKeyEnc())))
                .toList();
    }

    @Override
    public List<CapabilityConfigDTO> findAll(String capability) {
        return repository.findAllByCapability(capability).stream()
                .map(config -> CapabilityConfigDTO.from(config, maskApiKey(config.getApiKeyEnc())))
                .toList();
    }

    @Override
    public CapabilityConfigDTO create(String capability, CapabilityConfigUpdateRequestDTO request) {
        if (!StringUtils.hasText(request.getApiKey())) {
            throw new IllegalArgumentException("apiKey is required for new configuration.");
        }
        verifyEmbedModel(capability, request);
        CapabilityConfig config = CapabilityConfig.builder()
                .id(idGen.nextId())
                .capability(capability)
                .baseUrl(request.getBaseUrl())
                .apiKeyEnc(aesUtil.encrypt(request.getApiKey()))
                .modelName(request.getModelName())
                .extraConfig(extraConfigJson(request.getExtraConfig()))
                .enabled(false)
                .updatedBy(UserContextHolder.get().userId())
                .updatedAt(LocalDateTime.now())
                .build();
        CapabilityConfig saved = repository.insert(config);
        refreshSlot(capability);
        return CapabilityConfigDTO.from(saved, maskApiKey(saved.getApiKeyEnc()));
    }

    private void verifyEmbedModel(String capability, CapabilityConfigUpdateRequestDTO request) {
        ModelTypeEnum modelTypeEnum;
        try {
            modelTypeEnum = ModelTypeEnum.valueOf(capability.toUpperCase());
        }catch (Exception e) {
            throw new IllegalArgumentException("unsupported capability: " + capability);
        }
        if (modelTypeEnum == ModelTypeEnum.EMBEDDING || modelTypeEnum == ModelTypeEnum.MULTI_EMBEDDING) {
            Map<String, Object> extMap = request.getExtraConfig();
            if (null == extMap || null == extMap.get(EmbedParamEnum.DIMENSIONS.getKey())) {
                throw new IllegalArgumentException("dimensions is required for " + capability);
            }
        }
    }

    @Override
    public CapabilityConfigDTO update(String capability, Long id, CapabilityConfigUpdateRequestDTO request) {
        verifyEmbedModel(capability, request);
        CapabilityConfig existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));
        String apiKeyEnc = existing.getApiKeyEnc();
        if (StringUtils.hasText(request.getApiKey())) {
            apiKeyEnc = aesUtil.encrypt(request.getApiKey());
        }
        CapabilityConfig config = CapabilityConfig.builder()
                .id(id)
                .capability(capability)
                .baseUrl(request.getBaseUrl())
                .apiKeyEnc(apiKeyEnc)
                .modelName(request.getModelName())
                .extraConfig(extraConfigJson(request.getExtraConfig()))
                .enabled(existing.isEnabled())
                .updatedBy(UserContextHolder.get().userId())
                .updatedAt(LocalDateTime.now())
                .build();
        CapabilityConfig updated = repository.update(config);
        refreshSlot(capability);
        return CapabilityConfigDTO.from(updated, maskApiKey(updated.getApiKeyEnc()));
    }

    @Override
    public CapabilityConnectionTestResultDTO test(CapabilityConnectionTestRequestDTO request) {
        ModelTypeEnum capability;
        try {
            capability = ModelTypeEnum.valueOf(request.getCapability().toUpperCase());
        }catch (Exception e) {
            throw new IllegalArgumentException("unsupported capability: " + request.getCapability());
        }
        String apiKey = request.getApiKey();
        if (request.getConfigId() != null) {
            apiKey = repository.findById(request.getConfigId())
                    .map(c -> {
                        try { return aesUtil.decrypt(c.getApiKeyEnc()); }
                        catch (Exception e) { return null; }
                    })
                    .orElse(null);
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("apiKey is required for testing.");
        }

        return switch (capability) {
            case GENERATION -> {
                var client = new GenerationClient(request.getBaseUrl(), apiKey);
                var result = client.testConnection(request.getModelName());
                yield CapabilityConnectionTestResultDTO.builder()
                        .success(result.success())
                        .latencyMs(result.latencyMs())
                        .message(result.message())
                        .build();
            }
            case RERANK -> {
                var client = new RerankClient(request.getBaseUrl(), apiKey);
                var result = client.testConnection(request.getModelName());
                yield CapabilityConnectionTestResultDTO.builder()
                        .success(result.success())
                        .latencyMs(result.latencyMs())
                        .message(result.message())
                        .build();
            }
            case MULTI_EMBEDDING -> {
                var client = new MultiEmbeddingClient(request.getBaseUrl(), apiKey);
                var result = client.testConnection(request.getModelName());
                yield CapabilityConnectionTestResultDTO.builder()
                        .success(result.success())
                        .latencyMs(result.latencyMs())
                        .message(result.message())
                        .dimension(result.dimension())
                        .build();
            }
            case EMBEDDING -> {
                var client = new TextEmbeddingClient(request.getBaseUrl(), apiKey);
                var result = client.testConnection(request.getModelName());
                yield CapabilityConnectionTestResultDTO.builder()
                        .success(result.success())
                        .latencyMs(result.latencyMs())
                        .message(result.message())
                        .dimension(result.dimension())
                        .build();
            }
        };
    }

    @Override
    public void select(String capability, Long id) {
        repository.select(capability, id);
        // embedding types are mutually exclusive
        if (ModelTypeEnum.EMBEDDING.name().equals(capability)) {
            repository.disableAll(ModelTypeEnum.MULTI_EMBEDDING.name());
        } else if (ModelTypeEnum.MULTI_EMBEDDING.name().equals(capability)) {
            repository.disableAll(ModelTypeEnum.EMBEDDING.name());
        }
        // Both EMBEDDING and MULTI_EMBEDDING map to the EMBEDDING slot, so a single
        // refresh covers the mutual-exclusion toggle as well as GENERATION/RERANK.
        refreshSlot(capability);
    }

    @Override
    public void del(String capability, Long id) {
        repository.del(capability, id);
        refreshSlot(capability);
    }

    /**
     * Write-through cache refresh: after a config mutation, re-resolve the active
     * config for the affected slot and replace the cached client. If no active
     * config remains (e.g. the last one was deleted), invalidate the slot so the
     * next read re-queries the DB and surfaces the "not configured" error.
     */
    private void refreshSlot(String capability) {
        String slot = CapabilityResolver.slotFor(capability);
        java.util.Optional<CapabilityConfig> active = configResolver.activeForSlot(slot);
        if (active.isPresent()) {
            clientCacheManager.put(slot,
                    new ClientCacheManager.ResolvedClient(clientFactory.build(active.get()), active.get()));
        } else {
            clientCacheManager.invalidate(slot);
        }
    }

    private String extraConfigJson(java.util.Map<String, Object> extraConfig) {
        if (extraConfig == null || extraConfig.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(extraConfig);
        } catch (Exception e) {
            return null;
        }
    }

    private String maskApiKey(String apiKeyEnc) {
        try {
            String decrypted = aesUtil.decrypt(apiKeyEnc);
            if (decrypted.length() <= 8) {
                return "****";
            }
            return decrypted.substring(0, 4) + "****" + decrypted.substring(decrypted.length() - 4);
        } catch (Exception e) {
            return "****";
        }
    }
}
