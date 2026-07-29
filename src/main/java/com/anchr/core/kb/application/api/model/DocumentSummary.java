package com.anchr.core.kb.application.api.model;

/** Stable document snapshot for visibility and preview checks. */
public record DocumentSummary(
        String id,
        String kbId,
        String fileName,
        String title,
        String fileType,
        String mimeType,
        String objectKey,
        String previewObjectKey,
        long activeIndexGeneration
) {
}
