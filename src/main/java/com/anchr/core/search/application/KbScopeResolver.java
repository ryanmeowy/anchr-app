package com.anchr.core.search.application;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves visible knowledge base scope for search and conversation.
 */
@Service
@RequiredArgsConstructor
public class KbScopeResolver {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public List<String> resolveVisibleKbIds(List<String> requestedKbIds) {
        RequestUserContext context = UserContextHolder.get();
        List<String> activeIds = knowledgeBaseRepository.listActive(context.workspaceId(), 100, 0).stream()
                .map(KnowledgeBase::getId)
                .filter(StringUtils::hasText)
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
            if (!StringUtils.hasText(kbId)) {
                continue;
            }
            String normalized = kbId.trim();
            if (activeSet.contains(normalized)) {
                resolved.add(normalized);
            }
        }
        return resolved.stream().toList();
    }
}
