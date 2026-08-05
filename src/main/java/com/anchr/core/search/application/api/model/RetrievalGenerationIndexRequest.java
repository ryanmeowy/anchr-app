package com.anchr.core.search.application.api.model;

import com.anchr.core.common.model.BboxInfo;

import java.util.List;

public record RetrievalGenerationIndexRequest(
        String kbId,
        String assetId,
        long generation,
        String embeddingProfileFingerprint,
        List<SegmentValue> segments
) {
    public RetrievalGenerationIndexRequest {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public RetrievalGenerationIndexRequest(
            String kbId,
            String assetId,
            long generation,
            List<SegmentValue> segments
    ) {
        this(kbId, assetId, generation, null, segments);
    }

    public record SegmentValue(
            String segmentId,
            String kbId,
            String assetId,
            long indexGeneration,
            String assetType,
            String segmentType,
            String title,
            String contentText,
            String ocrText,
            Integer pageNo,
            Integer chunkOrder,
            List<BboxInfo> bbox,
            Integer imageWidth,
            Integer imageHeight,
            List<Float> embedding,
            String sourceRef,
            String thumbnail,
            String ocrSummary,
            List<String> tags,
            Long createdAt
    ) {
        public SegmentValue {
            bbox = bbox == null ? null : List.copyOf(bbox);
            embedding = embedding == null ? null : List.copyOf(embedding);
            tags = tags == null ? null : List.copyOf(tags);
        }
    }
}
