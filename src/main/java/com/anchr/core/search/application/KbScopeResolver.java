package com.anchr.core.search.application;

import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.domain.model.KnowledgeBase;
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

    private static final int ACTIVE_KB_PAGE_SIZE = 100;

    private final KnowledgeBaseService knowledgeBaseService;

    public List<String> resolveVisibleKbIds(List<String> requestedKbIds) {
        List<String> activeIds = listAllActiveIds();
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

    private List<String> listAllActiveIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        int page = 1;
        while (true) {
            KnowledgeBaseService.PagedResult<KnowledgeBase> result =
                    knowledgeBaseService.list(page, ACTIVE_KB_PAGE_SIZE);
            if (result.items().isEmpty()) {
                break;
            }
            result.items().stream()
                    .map(KnowledgeBase::getId)
                    .filter(StringUtils::hasText)
                    .forEach(ids::add);
            if (result.items().size() < ACTIVE_KB_PAGE_SIZE) {
                break;
            }
            page++;
        }
        return ids.stream().toList();
    }
}
