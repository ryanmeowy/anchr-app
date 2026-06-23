package com.anchr.core.integration.ai.adapter;

import com.anchr.core.integration.ai.client.CapabilityClientFactory;
import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.integration.ai.client.RerankClient;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible rerank adapter backed by capability_config.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class ConfigDrivenRerankAdapter implements SearchRerankPort {

    private final ClientCacheManager cacheManager;
    private final CapabilityClientFactory clientFactory;
    private final CapabilityResolver configResolver;

    @Override
    public List<RerankItem> rerank(String query, List<String> documents, Integer topN) {
        ClientCacheManager.ResolvedClient resolved = cacheManager.getOrBuild(
                CapabilityResolver.SLOT_RERANK, this::resolve);
        RerankClient client = (RerankClient) resolved.client();

        Map<String, Object> extraConfig = new LinkedHashMap<>();
        if (topN != null) extraConfig.put("top_n", topN);

        RerankClient.RerankResult result = client.rerank(
                resolved.config().getModelName(), query, documents, extraConfig);

        return result.items().stream()
                .map(item -> new RerankItem(item.index(), item.relevanceScore()))
                .toList();
    }

    private ClientCacheManager.ResolvedClient resolve() {
        CapabilityConfig config = configResolver.activeForSlot(CapabilityResolver.SLOT_RERANK)
                .orElseThrow(() -> new IllegalStateException(
                        "Rerank is not configured. Save config via PATCH /api/v1/settings/rerank."));
        return new ClientCacheManager.ResolvedClient(clientFactory.build(config), config);
    }
}
