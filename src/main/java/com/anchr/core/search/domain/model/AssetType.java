package com.anchr.core.search.domain.model;

import lombok.Getter;
import org.springframework.util.StringUtils;

import java.util.List;

@Getter
public enum AssetType {
    PDF("PDF", List.of("pdf"), List.of("application/pdf"), true, "P0"),
    IMAGE("IMAGE", List.of("png", "jpg", "jpeg", "webp"), List.of("image/png", "image/jpg", "image/jpeg", "image/webp"), true, "P0"),
    TXT("TXT", List.of("txt"), List.of("txt/plain"), true, "P0"),
    MARKDOWN("MARKDOWN", List.of("md", "markdown"), List.of("text/markdown", "text/x-markdown"), true, "P0"),
    ;

    private final String fileType;
    private final List<String> extensions;
    private final List<String> mimeTypes;
    private final boolean enabled;
    private final String priority;

    AssetType(String fileType, List<String> extensions, List<String> mimeTypes, boolean enabled, String priority) {
        this.fileType = fileType;
        this.extensions = extensions;
        this.mimeTypes = mimeTypes;
        this.enabled = enabled;
        this.priority = priority;
    }

    public AssetType byFileType(String fileType) {
        if (!StringUtils.hasText(fileType)) return null;
        for (AssetType type : values()) {
            if (type.fileType.equals(fileType)) return type;
        }
        return null;
    }

    public AssetType byMimeType(String mimeType) {
        if (!StringUtils.hasText(mimeType)) return null;
        for (AssetType type : values()) {
            if (type.mimeTypes.contains(mimeType)) return type;
        }
        return null;
    }
}
