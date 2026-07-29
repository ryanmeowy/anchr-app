package com.anchr.core.search.application.api.model;

import com.anchr.core.common.model.BboxInfo;

import java.util.List;

/** Retrieval-owned immutable document content snapshot. */
public record RetrievalDocumentChunk(
        String segmentId,
        String kbId,
        String assetId,
        long generation,
        String assetType,
        String segmentType,
        String title,
        String content,
        Integer pageNo,
        Integer chunkOrder,
        List<BboxInfo> bbox,
        String sourceRef
) {
    public RetrievalDocumentChunk {
        bbox = bbox == null ? null : List.copyOf(bbox);
    }
}
