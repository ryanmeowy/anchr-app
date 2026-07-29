package com.anchr.core.search.application.api.model;

public record RetrievalTopChunk(
        String segmentId,
        String kbId,
        String segmentType,
        String title,
        String content,
        String snippet,
        RetrievalExplain explain,
        Double score,
        Integer pageNo,
        RetrievalAnchor anchor,
        String sourceRef,
        String imagePreviewUrl,
        Long imagePreviewExpiresAt,
        String thumbnail,
        String ocrSummary
) {
}
