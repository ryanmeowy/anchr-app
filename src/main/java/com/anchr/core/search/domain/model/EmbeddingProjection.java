package com.anchr.core.search.domain.model;

/**
 * One embedding model call selected for a segment.
 */
public record EmbeddingProjection(
        InputType inputType,
        String source,
        SourceKind sourceKind,
        SegmentType projectionKind
) {

    public enum InputType {
        TEXT("text"),
        IMAGE("image");

        private final String requestValue;

        InputType(String requestValue) {
            this.requestValue = requestValue;
        }

        public String requestValue() {
            return requestValue;
        }
    }

    public enum SourceKind {
        CONTENT_TEXT,
        OCR_TEXT,
        ORIGINAL_IMAGE
    }
}
