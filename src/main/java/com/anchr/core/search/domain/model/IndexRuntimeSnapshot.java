package com.anchr.core.search.domain.model;

import com.anchr.core.search.domain.port.SearchEmbeddingPort.EmbeddingSession;

/**
 * One indivisible request-time view of the physical index and its vector space.
 */
public record IndexRuntimeSnapshot(
        String physicalIndex,
        EmbeddingProfile profile,
        EmbeddingSession embeddingSession,
        RetrievalPlan retrievalPlan
) {
    public IndexRuntimeSnapshot {
        if (physicalIndex == null || physicalIndex.isBlank()) {
            throw new IllegalArgumentException("Physical index is required");
        }
        if (profile == null || embeddingSession == null || retrievalPlan == null) {
            throw new IllegalArgumentException("Profile, session and retrieval plan are required");
        }
    }

    public record RetrievalPlan(
            int vectorSchemaVersion,
            String vectorField,
            boolean bm25Enabled,
            boolean knnEnabled
    ) {
        public static RetrievalPlan singleEmbeddingV1() {
            return new RetrievalPlan(1, "embedding", true, true);
        }
    }
}
