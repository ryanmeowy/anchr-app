package com.anchr.core.integration.ai.adapter;

import com.anchr.core.common.util.AesUtil;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.integration.ai.client.EmbeddingClient;
import com.anchr.core.integration.ai.client.MultiEmbeddingClient;
import com.anchr.core.integration.ai.client.TextEmbeddingClient;
import com.anchr.core.search.domain.port.SearchEmbeddingPort;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI-compatible embedding adapter backed by capability_config.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class ConfigDrivenEmbeddingAdapter implements SearchEmbeddingPort, IngestionEmbeddingPort {

    private final CapabilityConfigRepository configRepository;
    private final AesUtil aesUtil;

    // 1. 文字 + textEmbed        text
    // 2. 文字 + multiEmbed       {"text":""}
    // 3. 图片 + textEmbed        null
    // 4. 图片 + multiEmbed       {"image":""}
    public List<Float> embed(String source, String sourceType) {
        CapabilityConfig config = loadConfig();
        boolean isMulti = config.getCapability().equals("MULTI_EMBEDDING");
        EmbeddingClient client = isMulti
                ? new MultiEmbeddingClient(config.getBaseUrl(), decrypt(config.getApiKeyEnc()))
                : new TextEmbeddingClient(config.getBaseUrl(), decrypt(config.getApiKeyEnc()));
        Map<String, Object> extraMap = new HashMap<>();
        if (StringUtils.hasText(config.getExtraConfig())) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                extraMap = mapper.readValue(config.getExtraConfig(), new TypeReference<>() {});
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        EmbeddingClient.EmbedContext context = null;
        if ("text".equals(sourceType)) {
            if (isMulti) {
                Map<String, Object> map = new HashMap<>();
                map.put("text", source);
                Map<String, Object> contentMap = new HashMap<>();
                contentMap.put("contents", Lists.newArrayList(map));
                context = EmbeddingClient.EmbedContext
                        .builder()
                        .modelName(config.getModelName())
                        .extraConfig(extraMap)
                        .contentMap(contentMap)
                        .build();
            } else {
                context = EmbeddingClient.EmbedContext
                        .builder()
                        .modelName(config.getModelName())
                        .extraConfig(extraMap)
                        .texts(List.of(source))
                        .build();
            }
        }

        if ("image".equals(sourceType)) {
            if (isMulti) {
                Map<String, Object> map = new HashMap<>();
                map.put("image", source);
                Map<String, Object> contentMap = new HashMap<>();
                contentMap.put("contents", Lists.newArrayList(map));
                context = EmbeddingClient.EmbedContext
                        .builder()
                        .modelName(config.getModelName())
                        .extraConfig(extraMap)
                        .contentMap(contentMap)
                        .build();
            }
        }
        if (null == context) {
            return Lists.newArrayList();
        }
        return client.embed(context).vector();
    }

    private CapabilityConfig loadConfig() {
        Optional<CapabilityConfig> embedCfgOpt = configRepository.findByCapability("EMBEDDING").stream().findFirst();
        return embedCfgOpt.orElseGet(() -> configRepository.findByCapability("MULTI_EMBEDDING").stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Embedding is not configured. Save config via PATCH /api/v1/settings/embedding.")));
    }

    private String decrypt(String encrypted) {
        try {
            return aesUtil.decrypt(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt embedding apiKey.", e);
        }
    }
}
