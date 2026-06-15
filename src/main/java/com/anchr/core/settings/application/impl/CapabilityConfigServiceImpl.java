package com.anchr.core.settings.application.impl;

import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.integration.ai.EmbeddingClient;
import com.anchr.core.integration.ai.GenerationClient;
import com.anchr.core.integration.ai.MultiEmbeddingClient;
import com.anchr.core.integration.ai.RerankClient;
import com.anchr.core.settings.application.CapabilityConfigService;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigUpdateRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConnectionTestRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConnectionTestResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Default implementation for capability configuration.
 */
@Service
@RequiredArgsConstructor
public class CapabilityConfigServiceImpl implements CapabilityConfigService {


    private final CapabilityConfigRepository repository;
    private final AesUtil aesUtil;
    private final IdGen idGen;

    @Override
    public List<CapabilityConfigDTO> get(String capability) {
        return repository.findByCapability(capability).stream()
                .map(config -> CapabilityConfigDTO.from(config, maskApiKey(config.getApiKeyEnc())))
                .toList();
    }

    @Override
    public CapabilityConfigDTO save(String capability, CapabilityConfigUpdateRequestDTO request) {
        CapabilityConfig existing = repository.findByCapability(capability).stream().findFirst().orElse(null);
        String apiKeyEnc;

        if (StringUtils.hasText(request.getApiKey())) {
            apiKeyEnc = aesUtil.encrypt(request.getApiKey());
        } else if (existing != null) {
            apiKeyEnc = existing.getApiKeyEnc();
        } else {
            throw new IllegalArgumentException("apiKey is required for new configuration.");
        }

        CapabilityConfig config = CapabilityConfig.builder()
                .id(existing != null ? existing.getId() : idGen.nextId())
                .capability(capability)
                .baseUrl(request.getBaseUrl())
                .apiKeyEnc(apiKeyEnc)
                .modelName(request.getModelName())
                .extraConfig(extraConfigJson(request.getExtraConfig()))
                .enabled(true)
                .updatedBy(UserContextHolder.get().userId())
                .updatedAt(LocalDateTime.now())
                .build();

        CapabilityConfig saved = repository.upsert(config);
        return CapabilityConfigDTO.from(saved, maskApiKey(saved.getApiKeyEnc()));
    }

    @Override
    public CapabilityConnectionTestResultDTO test(CapabilityConnectionTestRequestDTO request) {
        String capability = request.getCapability() != null
                ? request.getCapability().toUpperCase() : CAPABILITY_EMBEDDING;

        return switch (capability) {
            case CAPABILITY_GENERATION -> {
                var client = new GenerationClient(request.getBaseUrl(), request.getApiKey());
                var result = client.testConnection(request.getModelName());
                yield CapabilityConnectionTestResultDTO.builder()
                        .success(result.success())
                        .latencyMs(result.latencyMs())
                        .message(result.message())
                        .build();
            }
            case CAPABILITY_RERANK -> {
                var client = new RerankClient(request.getBaseUrl(), request.getApiKey());
                var result = client.testConnection(request.getModelName());
                yield CapabilityConnectionTestResultDTO.builder()
                        .success(result.success())
                        .latencyMs(result.latencyMs())
                        .message(result.message())
                        .build();
            }
            case CAPABILITY_MULTI_EMBEDDING -> {
                var client = new MultiEmbeddingClient(request.getBaseUrl(), request.getApiKey());
                var result = client.testConnection(request.getModelName());
                yield CapabilityConnectionTestResultDTO.builder()
                        .success(result.success())
                        .latencyMs(result.latencyMs())
                        .message(result.message())
                        .dimension(result.dimension())
                        .build();
            }
            default -> {
                var client = new EmbeddingClient(request.getBaseUrl(), request.getApiKey());
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
    public void del(String capability, Long id) {
        repository.del(capability, id);
    }


    private String extraConfigJson(java.util.Map<String, Object> extraConfig) {
        if (extraConfig == null || extraConfig.isEmpty()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(extraConfig);
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
