package com.anchr.core.ingestion.application.model;

import com.anchr.core.common.model.BboxInfo;

import java.util.List;

/** Knowledge Content-owned value prepared for one Retrieval generation write. */
public record IngestionIndexSegment(
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
    public IngestionIndexSegment {
        bbox = bbox == null ? null : List.copyOf(bbox);
        embedding = embedding == null ? null : List.copyOf(embedding);
        tags = tags == null ? null : List.copyOf(tags);
    }

    public IngestionIndexSegment withEmbedding(List<Float> value) {
        return new IngestionIndexSegment(
                segmentId, kbId, assetId, indexGeneration, assetType, segmentType,
                title, contentText, ocrText, pageNo, chunkOrder, bbox, imageWidth,
                imageHeight, value, sourceRef, thumbnail, ocrSummary, tags, createdAt);
    }
}
