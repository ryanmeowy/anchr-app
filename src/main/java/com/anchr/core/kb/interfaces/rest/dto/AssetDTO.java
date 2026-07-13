package com.anchr.core.kb.interfaces.rest.dto;

import com.anchr.core.kb.domain.model.Asset;
import lombok.Builder;
import lombok.Value;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Document asset response DTO.
 */
@Value
@Builder
public class AssetDTO {

    String id;
    String kbId;
    String fileName;
    String title;
    String fileType;
    String mimeType;
    Long sizeBytes;
    String fileHash;
    String sourceUrl;
    Integer versionNo;
    boolean previewAvailable;
    String parseStatus;
    String indexStatus;
    int segmentCount;
    int indexedSegmentCount;
    String embeddingProfile;
    String errorCode;
    String errorMessage;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static AssetDTO from(Asset document) {
        return AssetDTO.builder()
                .id(document.getId())
                .kbId(document.getKbId())
                .fileName(document.getFileName())
                .title(document.getTitle())
                .fileType(document.getFileType())
                .mimeType(document.getMimeType())
                .sizeBytes(document.getSizeBytes())
                .fileHash(document.getFileHash())
                .sourceUrl(document.getSourceUrl())
                .versionNo(document.getVersionNo())
                .previewAvailable(isPreviewAvailable(document))
                .parseStatus(document.getParseStatus().name())
                .indexStatus(document.getIndexStatus().name())
                .segmentCount(document.getSegmentCount())
                .indexedSegmentCount(document.getIndexedSegmentCount())
                .embeddingProfile(document.getEmbeddingProfile())
                .errorCode(document.getErrorCode())
                .errorMessage(document.getErrorMessage())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }

    private static boolean isPreviewAvailable(Asset document) {
        return StringUtils.hasText(document.getPreviewObjectKey())
                || StringUtils.hasText(document.getObjectKey())
                || isHttpUrl(document.getSourceUrl());
    }

    private static boolean isHttpUrl(String value) {
        return StringUtils.hasText(value)
                && (value.trim().startsWith("http://") || value.trim().startsWith("https://"));
    }
}
