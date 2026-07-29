package com.anchr.core.conversation.application.acl;

import com.anchr.core.kb.application.api.KnowledgeContentQueryApi;
import com.anchr.core.kb.application.api.model.KnowledgeBaseSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Conversation-owned translation of Knowledge Content scope facts. */
@Component
@RequiredArgsConstructor
public class ConversationKnowledgeAcl {

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
}
