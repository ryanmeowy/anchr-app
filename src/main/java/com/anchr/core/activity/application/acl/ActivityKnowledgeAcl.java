package com.anchr.core.activity.application.acl;

import com.anchr.core.kb.application.api.KnowledgeContentQueryApi;
import com.anchr.core.kb.application.api.model.KnowledgeBaseSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/** Translates Knowledge Content summaries for Activity queries. */
@Component
@RequiredArgsConstructor
public class ActivityKnowledgeAcl {

    private final KnowledgeContentQueryApi knowledgeContentQueryApi;

    public Map<String, String> findActiveNames(Collection<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Map.of();
        }
        return knowledgeContentQueryApi.findActiveKnowledgeBases(kbIds).stream()
                .filter(summary -> StringUtils.hasText(summary.id()) && StringUtils.hasText(summary.name()))
                .collect(Collectors.toMap(
                        KnowledgeBaseSummary::id,
                        KnowledgeBaseSummary::name,
                        (first, second) -> first));
    }
}
