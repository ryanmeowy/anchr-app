package com.anchr.core.search.application.api.model;

import java.util.List;

public record RetrievalHit(
        String segmentType,
        String title,
        String content,
        String resultType,
        String assetType,
        String snippet,
        Integer pageNo,
        Double score,
        RetrievalExplain explain,
        RetrievalAnchor anchor,
        String thumbnail,
        String ocrSummary,
        Integer totalHits,
        List<RetrievalTopChunk> topChunks,
        String segmentId,
        String kbId,
        String assetId,
        String sourceRef,
        String imagePreviewUrl,
        Long imagePreviewExpiresAt
) {
    public RetrievalHit {
        topChunks = topChunks == null ? List.of() : List.copyOf(topChunks);
    }
}
