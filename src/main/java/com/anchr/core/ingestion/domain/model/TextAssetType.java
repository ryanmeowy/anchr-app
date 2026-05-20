package com.anchr.core.ingestion.domain.model;

import java.util.Set;

/**
 * Supported text asset types in Phase 1.
 */
public enum TextAssetType {
    PDF(Set.of("pdf"), Set.of("application/pdf")),
    TXT(Set.of("txt"), Set.of("text/plain")),
    MARKDOWN(Set.of("md", "markdown"), Set.of("text/markdown", "text/x-markdown", "text/plain"));

    private final Set<String> extensions;
    private final Set<String> mimeTypes;

    TextAssetType(Set<String> extensions, Set<String> mimeTypes) {
        this.extensions = extensions;
        this.mimeTypes = mimeTypes;
    }

    public static boolean isSupported(String fileName, String contentType) {
        String ext = resolveExtension(fileName);
        String normalizedContentType = contentType == null ? "" : contentType.toLowerCase();
        for (TextAssetType type : values()) {
            if (type.extensions.contains(ext) || type.mimeTypes.contains(normalizedContentType)) {
                return true;
            }
        }
        return false;
    }

    public static String resolveExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }
}
