package com.anchr.core.ingestion.application.impl;

import org.springframework.util.StringUtils;

/** Builds and validates the OSS image prefix owned by one asset generation. */
public final class IngestionImagePaths {

    /** Docling v3 contract literal; the actual App ownership boundary is asset + generation. */
    public static final String DOCLING_OBJECT_KEY_LAYOUT = "ATTEMPT_PREFIX_V1";

    private IngestionImagePaths() {
    }

    public static String imagePrefix(String configuredPrefix,
                                     String assetId,
                                     long targetGeneration) {
        String suffix = generationPrefix(assetId, targetGeneration) + "images/";
        String base = trimSlashes(configuredPrefix);
        return base.isEmpty() ? suffix : base + "/" + suffix;
    }

    private static String generationPrefix(String assetId, long targetGeneration) {
        if (targetGeneration < 1) {
            throw new IllegalArgumentException("targetGeneration must be positive.");
        }
        return "ingestion/assets/" + safeSegment(assetId, "assetId")
                + "/generations/" + targetGeneration + "/";
    }

    private static String trimSlashes(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("^/+|/+$", "");
    }

    private static String safeSegment(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        String segment = value.trim();
        if (".".equals(segment) || "..".equals(segment)
                || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0
                || segment.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is not a safe object-key segment.");
        }
        return segment;
    }
}
