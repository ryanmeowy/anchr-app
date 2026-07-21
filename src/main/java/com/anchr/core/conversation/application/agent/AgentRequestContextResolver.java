package com.anchr.core.conversation.application.agent;

import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Resolves client-provided IDs back to active server-side resources before
 * exposing their metadata to the Agent model.
 */
@Component
@RequiredArgsConstructor
public class AgentRequestContextResolver {
    private static final int MAX_CONTEXT_KNOWLEDGE_BASES = 50;
    private static final int MAX_CONTEXT_ASSETS = 20;
    private static final int MAX_NAME_LENGTH = 300;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final AssetRepository assetRepository;

    public AgentRequestContext resolve(AgentRunRequest request) {
        List<String> requestedKbIds = normalizedIds(request.request().getKbIds());
        List<String> requestedAssetIds = normalizedIds(request.request().getAssetIdList());

        Map<String, KnowledgeBase> activeKnowledgeBases = new LinkedHashMap<>();
        for (KnowledgeBase knowledgeBase : knowledgeBaseRepository.listActiveByIds(requestedKbIds)) {
            activeKnowledgeBases.put(knowledgeBase.getId(), knowledgeBase);
        }
        List<String> authorizedKbIds = requestedKbIds.stream()
                .filter(activeKnowledgeBases::containsKey)
                .toList();

        List<Asset> resolvedAssets = resolveAssets(requestedAssetIds, authorizedKbIds);
        List<AgentRequestContext.KnowledgeBaseRef> knowledgeBases = authorizedKbIds.stream()
                .limit(MAX_CONTEXT_KNOWLEDGE_BASES)
                .map(activeKnowledgeBases::get)
                .map(kb -> new AgentRequestContext.KnowledgeBaseRef(
                        kb.getId(), safeText(kb.getName())))
                .toList();
        List<AgentRequestContext.AssetRef> assets = resolvedAssets.stream()
                .limit(MAX_CONTEXT_ASSETS)
                .map(asset -> new AgentRequestContext.AssetRef(
                        asset.getId(),
                        asset.getKbId(),
                        safeText(asset.getFileName()),
                        safeText(asset.getTitle()),
                        safeText(StringUtils.hasText(asset.getMimeType())
                                ? asset.getMimeType() : asset.getFileType())))
                .toList();

        return new AgentRequestContext(
                "ANCHR_REQUEST_CONTEXT",
                1,
                true,
                requestedAssetIds.isEmpty() ? "KNOWLEDGE_BASE" : "ASSET",
                authorizedKbIds.size(),
                resolvedAssets.size(),
                authorizedKbIds.size() > knowledgeBases.size(),
                resolvedAssets.size() > assets.size(),
                knowledgeBases,
                assets
        );
    }

    private List<Asset> resolveAssets(List<String> assetIds, List<String> kbIds) {
        List<Asset> assets = new ArrayList<>();
        for (String assetId : assetIds) {
            Asset resolved = null;
            for (String kbId : kbIds) {
                resolved = assetRepository.findActiveById(kbId, assetId).orElse(null);
                if (resolved != null) break;
            }
            if (resolved != null) assets.add(resolved);
        }
        return assets;
    }

    private List<String> normalizedIds(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) result.add(value.trim());
        }
        return result.stream().toList();
    }

    private String safeText(String value) {
        if (!StringUtils.hasText(value)) return "";
        String normalized = value.trim()
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
        return normalized.length() <= MAX_NAME_LENGTH
                ? normalized : normalized.substring(0, MAX_NAME_LENGTH);
    }
}
