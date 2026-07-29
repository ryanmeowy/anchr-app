package com.anchr.core.kb.application.api;

import com.anchr.core.kb.application.api.model.DocumentSummary;
import com.anchr.core.kb.application.api.model.KnowledgeBaseSummary;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Public read-only capabilities exposed by Knowledge Content. */
public interface KnowledgeContentQueryApi {

    List<KnowledgeBaseSummary> listActiveKnowledgeBases();

    Optional<KnowledgeBaseSummary> findActiveKnowledgeBase(String kbId);

    Optional<DocumentSummary> findActiveDocument(String kbId, String assetId);

    Map<String, Long> findActiveIndexGenerations(Collection<String> assetIds);
}
