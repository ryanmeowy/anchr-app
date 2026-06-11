package com.anchr.core.integration.multimodal.embedding;

import com.anchr.core.auth.infrastructure.AesUtil;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OpenAI-compatible rerank adapter backed by capability_config.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class ConfigDrivenRerankAdapter implements SearchRerankPort {

    private final CapabilityConfigRepository configRepository;
    private final AesUtil aesUtil;

    @Override
    public List<RerankItem> rerank(String query, List<String> documents, Integer topN) {
        CapabilityConfig config = loadConfig();

        java.util.Map<String, Object> extraConfig = new java.util.LinkedHashMap<>();
        if (topN != null) extraConfig.put("top_n", topN);

        RerankClient client = new RerankClient(config.getBaseUrl(), decrypt(config.getApiKeyEnc()));
        RerankClient.RerankResult result = client.rerank(config.getModelName(), query, documents, extraConfig);

        return result.items().stream()
                .map(item -> new RerankItem(item.index(), item.relevanceScore()))
                .toList();
    }

    private CapabilityConfig loadConfig() {
        return configRepository.findByCapability("RERANK")
                .orElseThrow(() -> new IllegalStateException(
                        "Rerank is not configured. Save config via PATCH /api/v1/settings/rerank."));
    }

    private String decrypt(String encrypted) {
        try { return aesUtil.decrypt(encrypted); }
        catch (Exception e) { throw new IllegalStateException("Failed to decrypt rerank apiKey.", e); }
    }
}
