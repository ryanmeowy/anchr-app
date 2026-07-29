package com.anchr.core.conversation.application.acl;

import com.anchr.core.conversation.application.model.ConversationDocumentReference;
import com.anchr.core.conversation.application.model.ConversationKnowledgeBaseReference;
import com.anchr.core.kb.application.api.KnowledgeContentQueryApi;
import com.anchr.core.kb.application.api.model.DocumentSummary;
import com.anchr.core.kb.application.api.model.KnowledgeBaseSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Conversation-owned translation of Knowledge Content scope facts. */
@Component
@RequiredArgsConstructor
public class ConversationKnowledgeAcl {

    private final KnowledgeContentQueryApi knowledgeContentQueryApi;

    public List<String> resolveVisibleKbIds(List<String> requestedKbIds) {
        return resolveVisibleKnowledgeBases(requestedKbIds).stream()
                .map(ConversationKnowledgeBaseReference::id)
                .toList();
    }

    public List<ConversationKnowledgeBaseReference> resolveVisibleKnowledgeBases(
            List<String> requestedKbIds) {
        Map<String, KnowledgeBaseSummary> active = knowledgeContentQueryApi
                .listActiveKnowledgeBases().stream()
                .filter(summary -> summary != null && StringUtils.hasText(summary.id()))
                .collect(Collectors.toMap(
                        summary -> summary.id().trim(), Function.identity(),
                        (left, right) -> left, java.util.LinkedHashMap::new));
        if (active.isEmpty()) {
            return List.of();
        }
        List<String> resolvedIds;
        if (requestedKbIds == null || requestedKbIds.isEmpty()) {
            resolvedIds = List.copyOf(active.keySet());
        } else {
            Set<String> activeIds = new LinkedHashSet<>(active.keySet());
            LinkedHashSet<String> resolved = new LinkedHashSet<>();
            for (String kbId : requestedKbIds) {
                if (StringUtils.hasText(kbId)) {
                    String normalized = kbId.trim();
                    if (activeIds.contains(normalized)) {
                        resolved.add(normalized);
                    }
                }
            }
            resolvedIds = List.copyOf(resolved);
        }
        return resolvedIds.stream()
                .map(active::get)
                .map(summary -> new ConversationKnowledgeBaseReference(
                        summary.id(), summary.name()))
                .toList();
    }

    public Optional<ConversationDocumentReference> findActiveDocument(
            List<String> authorizedKbIds, String assetId) {
        if (!StringUtils.hasText(assetId) || authorizedKbIds == null) {
            return Optional.empty();
        }
        String normalizedAssetId = assetId.trim();
        for (String kbId : normalizedIds(authorizedKbIds)) {
            Optional<DocumentSummary> summary = knowledgeContentQueryApi.findActiveDocument(
                    kbId, normalizedAssetId);
            if (summary.isPresent()) {
                return summary.map(this::toReference);
            }
        }
        return Optional.empty();
    }

    public List<ConversationDocumentReference> searchActiveDocuments(
            List<String> authorizedKbIds, String keyword, int limitPerKnowledgeBase) {
        if (!StringUtils.hasText(keyword)
                || authorizedKbIds == null
                || limitPerKnowledgeBase < 1) {
            return List.of();
        }
        java.util.LinkedHashMap<String, ConversationDocumentReference> result =
                new java.util.LinkedHashMap<>();
        for (String kbId : normalizedIds(authorizedKbIds)) {
            for (DocumentSummary summary : knowledgeContentQueryApi.searchActiveDocuments(
                    kbId, keyword.trim(), limitPerKnowledgeBase)) {
                if (summary != null && StringUtils.hasText(summary.id())) {
                    result.putIfAbsent(summary.id(), toReference(summary));
                }
            }
        }
        return List.copyOf(result.values());
    }

    private List<String> normalizedIds(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                result.add(value.trim());
            }
        }
        return List.copyOf(result);
    }

    private ConversationDocumentReference toReference(DocumentSummary source) {
        return new ConversationDocumentReference(
                source.id(), source.kbId(), source.fileName(), source.title(),
                source.fileType(), source.mimeType(), source.activeIndexGeneration(),
                source.segmentCount());
    }
}
