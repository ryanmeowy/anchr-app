package com.anchr.core.search.application.acl;

import com.anchr.core.kb.application.api.KnowledgeContentQueryApi;
import com.anchr.core.kb.application.api.model.DocumentSummary;
import com.anchr.core.kb.application.api.model.KnowledgeBaseSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Retrieval-owned translation of Knowledge Content read capabilities. */
@Component
@RequiredArgsConstructor
public class SearchKnowledgeAcl {

    private final KnowledgeContentQueryApi knowledgeContentQueryApi;

    public List<String> resolveVisibleKbIds(List<String> requestedKbIds) {
        List<String> activeIds = knowledgeContentQueryApi.listActiveKnowledgeBases().stream()
                .map(KnowledgeBaseSummary::id)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (activeIds.isEmpty()) {
            return List.of();
        }
        if (requestedKbIds == null || requestedKbIds.isEmpty()) {
            return activeIds;
        }
        Set<String> activeSet = new LinkedHashSet<>(activeIds);
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String kbId : requestedKbIds) {
            if (StringUtils.hasText(kbId)) {
                String normalized = kbId.trim();
                if (activeSet.contains(normalized)) {
                    resolved.add(normalized);
                }
            }
        }
        return List.copyOf(resolved);
    }

    public Map<String, Long> findActiveIndexGenerations(Collection<String> assetIds) {
        return knowledgeContentQueryApi.findActiveIndexGenerations(assetIds);
    }

    public Optional<KnowledgeBaseSummary> findActiveKnowledgeBase(String kbId) {
        return knowledgeContentQueryApi.findActiveKnowledgeBase(kbId);
    }

    public Optional<DocumentSummary> findActiveDocument(String kbId, String assetId) {
        return knowledgeContentQueryApi.findActiveDocument(kbId, assetId);
    }
}
