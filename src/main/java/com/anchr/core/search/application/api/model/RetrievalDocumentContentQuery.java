package com.anchr.core.search.application.api.model;

/** Ordered content request for one explicit Asset generation. */
public record RetrievalDocumentContentQuery(
        String kbId,
        String assetId,
        long generation,
        Integer afterChunkOrder,
        String afterSegmentId,
        int limit
) {
}
