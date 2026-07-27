package com.anchr.core.ingestion.application.artifact;

import org.springframework.util.StringUtils;

/** Builds and validates object prefixes owned by one ingestion parse attempt. */
public final class IngestionArtifactPaths {

    public static final String ATTEMPT_PREFIX_LAYOUT = "ATTEMPT_PREFIX_V1";

    private IngestionArtifactPaths() {}

    public static String imagePrefix(String configuredPrefix,
                                     String taskId,
                                     String itemId,
                                     int parseAttempt) {
        String suffix = parseAttemptPrefix(taskId, itemId, parseAttempt) + "images/";
        String base = trimSlashes(configuredPrefix);
        return base.isEmpty() ? suffix : base + "/" + suffix;
    }

    public static String parseAttemptPrefix(String taskId,
                                            String itemId,
                                            int parseAttempt) {
        if (parseAttempt < 1) {
            throw new IllegalArgumentException("parseAttempt must be positive.");
        }
        return "ingestion/" + safeSegment(taskId, "taskId")
                + "/" + safeSegment(itemId, "itemId")
                + "/parse/" + parseAttempt + "/";
    }

    public static boolean isExpectedImagePrefix(String prefix,
                                                String taskId,
                                                String itemId,
                                                int parseAttempt) {
        if (!StringUtils.hasText(prefix)) return false;
        String normalized = normalizePrefix(prefix);
        String suffix = parseAttemptPrefix(taskId, itemId, parseAttempt) + "images/";
        return normalized.equals(suffix) || normalized.endsWith("/" + suffix);
    }

    public static boolean isExpectedParsePrefix(String prefix,
                                                String taskId,
                                                String itemId,
                                                int parseAttempt) {
        return StringUtils.hasText(prefix)
                && normalizePrefix(prefix).equals(
                        parseAttemptPrefix(taskId, itemId, parseAttempt));
    }

    public static String parsePrefixFromArtifactKey(String objectKey) {
        if (!StringUtils.hasText(objectKey)) return null;
        String normalized = objectKey.trim();
        int jobs = normalized.indexOf("/jobs/");
        if (!normalized.startsWith("ingestion/") || jobs < 0) return null;
        String prefix = normalized.substring(0, jobs + 1);
        return prefix.matches(
                "ingestion/(?!\\.{1,2}/)[^/]+/(?!\\.{1,2}/)[^/]+/parse/[1-9][0-9]*/")
                ? prefix : null;
    }

    public static String imagePrefixFromObjectKey(String objectKey) {
        if (!StringUtils.hasText(objectKey)) return null;
        String normalized = objectKey.trim();
        int marker = normalized.indexOf("/ingestion/");
        int start = marker >= 0 ? marker + 1 : 0;
        String owned = normalized.substring(start);
        int images = owned.indexOf("/images/");
        if (images < 0) return null;
        String suffix = owned.substring(0, images + "/images/".length());
        if (!suffix.matches(
                "ingestion/(?!\\.{1,2}/)[^/]+/(?!\\.{1,2}/)[^/]+/parse/[1-9][0-9]*/images/")) {
            return null;
        }
        return normalized.substring(0, start) + suffix;
    }

    private static String normalizePrefix(String value) {
        String normalized = value.trim();
        return normalized.endsWith("/") ? normalized : normalized + "/";
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
