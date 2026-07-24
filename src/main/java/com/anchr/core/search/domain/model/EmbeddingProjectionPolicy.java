package com.anchr.core.search.domain.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Selects the only valid embedding input for each segment type.
 */
public final class EmbeddingProjectionPolicy {

    private EmbeddingProjectionPolicy() {
    }

    public static Optional<EmbeddingProjection> select(
            Profile profile,
            String assetType,
            SegmentType segmentType,
            String contentText,
            String ocrText,
            String imageSource
    ) {
        if (profile == null || segmentType == null) {
            return Optional.empty();
        }
        return switch (segmentType) {
            case TEXT_CHUNK -> !isImage(assetType)
                    ? textProjection(
                            contentText,
                            EmbeddingProjection.SourceKind.CONTENT_TEXT,
                            SegmentType.TEXT_CHUNK)
                    : Optional.empty();
            case IMAGE_OCR_BLOCK -> profile == Profile.TEXT && isImage(assetType)
                    ? textProjection(
                            ocrText,
                            EmbeddingProjection.SourceKind.OCR_TEXT,
                            SegmentType.IMAGE_OCR_BLOCK)
                    : Optional.empty();
            case IMAGE_VISUAL -> profile == Profile.MULTI && isImage(assetType)
                    ? imageProjection(imageSource)
                    : Optional.empty();
        };
    }

    public static boolean requiresImageVisual(Profile profile, String assetType) {
        return profile == Profile.MULTI && isImage(assetType);
    }

    private static Optional<EmbeddingProjection> textProjection(
            String source,
            EmbeddingProjection.SourceKind sourceKind,
            SegmentType segmentType
    ) {
        if (!hasText(source)) {
            return Optional.empty();
        }
        return Optional.of(new EmbeddingProjection(
                EmbeddingProjection.InputType.TEXT,
                source,
                sourceKind,
                segmentType));
    }

    private static Optional<EmbeddingProjection> imageProjection(String source) {
        if (!hasText(source)) {
            return Optional.empty();
        }
        return Optional.of(new EmbeddingProjection(
                EmbeddingProjection.InputType.IMAGE,
                source,
                EmbeddingProjection.SourceKind.ORIGINAL_IMAGE,
                SegmentType.IMAGE_VISUAL));
    }

    private static boolean isImage(String assetType) {
        return "IMAGE".equalsIgnoreCase(assetType);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public enum Profile {
        TEXT,
        MULTI;

        public static Profile fromCapability(String capability) {
            if (!hasText(capability)) {
                throw new IllegalArgumentException("Embedding capability cannot be blank.");
            }
            return "MULTI_EMBEDDING".equals(
                    capability.trim().toUpperCase(Locale.ROOT))
                    ? MULTI : TEXT;
        }

        public static Profile fromMulti(boolean multi) {
            return multi ? MULTI : TEXT;
        }
    }
}
