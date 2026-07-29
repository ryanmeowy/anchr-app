package com.anchr.core.search.application.api.model;

import java.util.List;

/** Immutable citation explanation request. */
public record RetrievalCitationReasonRequest(
        String question,
        String rewrittenQuery,
        String answer,
        List<CitationGroup> citations
) {
    public RetrievalCitationReasonRequest {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public record CitationGroup(
            Integer citationIndex,
            String assetId,
            List<CitationChunk> chunks
    ) {
        public CitationGroup {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }

    public record CitationChunk(
            String segmentId,
            String content,
            Double score,
            List<String> hitSources,
            String matchSummary
    ) {
        public CitationChunk {
            hitSources = hitSources == null ? List.of() : List.copyOf(hitSources);
        }
    }
}
