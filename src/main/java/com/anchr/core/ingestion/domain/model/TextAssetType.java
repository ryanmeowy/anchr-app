package com.anchr.core.ingestion.domain.model;

import java.util.Set;

/**
 * Supported text asset types in Phase 1.
 */
public enum TextAssetType {
    PDF(Set.of("pdf"), Set.of("application/pdf")),
    TXT(Set.of("txt"), Set.of("text/plain")),
    MARKDOWN(Set.of("md", "markdown"), Set.of("text/markdown", "text/x-markdown", "text/plain")),
    DOCX(Set.of("docx"), Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
    XLSX(Set.of("xlsx", "xls"), Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel")),
    CSV(Set.of("csv"), Set.of("text/csv", "application/csv")),
    HTML(Set.of("html", "htm"), Set.of("text/html", "application/xhtml+xml")),
    PPTX(Set.of("pptx"), Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation")),
    ZIP(Set.of("zip"), Set.of("application/zip", "application/x-zip-compressed"));

    private final Set<String> extensions;
    private final Set<String> mimeTypes;

    TextAssetType(Set<String> extensions, Set<String> mimeTypes) {
        this.extensions = extensions;
        this.mimeTypes = mimeTypes;
    }

    public static boolean isSupported(String fileName, String contentType) {
        String ext = resolveExtension(fileName);
        String normalizedContentType = normalizeMimeType(contentType);
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
        String normalized = fileName.trim();
        int query = normalized.indexOf('?');
        if (query >= 0) {
            normalized = normalized.substring(0, query);
        }
        int fragment = normalized.indexOf('#');
        if (fragment >= 0) {
            normalized = normalized.substring(0, fragment);
        }
        int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        if (slash >= 0 && slash < normalized.length() - 1) {
            normalized = normalized.substring(slash + 1);
        }
        int dot = normalized.lastIndexOf('.');
        if (dot < 0 || dot == normalized.length() - 1) {
            return "";
        }
        return normalized.substring(dot + 1).toLowerCase();
    }

    public static String normalizeMimeType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String value = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return value.trim().toLowerCase();
    }
}
