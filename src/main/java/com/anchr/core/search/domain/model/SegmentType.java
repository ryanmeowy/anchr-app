package com.anchr.core.search.domain.model;

/**
 * Unified segment categories in kb retrieval.
 */
public enum SegmentType {
    TEXT_CHUNK,
    IMAGE_OCR_BLOCK,
    IMAGE_VISUAL,
    DOCUMENT_IMAGE;

    public static boolean isImageVisual(String value) {
        return value != null
                && IMAGE_VISUAL.name().equalsIgnoreCase(value.trim());
    }
}
