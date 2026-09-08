package com.anchr.core.common.util;

import org.springframework.util.StringUtils;

/** Display names are independent of storage keys. */
public final class CitationFileName {
    private CitationFileName() {}

    public static String resolve(String fileName, String sourceRef, String segmentType, String title) {
        if (StringUtils.hasText(fileName)) return fileName.trim();
        // Preserve the legacy image fallback when the parent document is unavailable.
        if ("DOCUMENT_IMAGE".equalsIgnoreCase(segmentType) && StringUtils.hasText(title)) {
            return title.trim();
        }
        if (!StringUtils.hasText(sourceRef)) return null;
        String path = sourceRef.trim();
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int fragment = path.indexOf('#');
        if (fragment >= 0) path = path.substring(0, fragment);
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 || slash == path.length() - 1 ? path : path.substring(slash + 1);
    }
}
