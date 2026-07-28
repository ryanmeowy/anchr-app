package com.anchr.core.kb.interfaces.rest.dto;

import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentAvailabilityStatus;
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
    Integer versionNo;
    boolean previewAvailable;
    String parseStatus;
    String indexStatus;
    String availabilityStatus;
    int segmentCount;
    int indexedSegmentCount;
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
                .versionNo(document.getVersionNo())
                .previewAvailable(isPreviewAvailable(document))
                .parseStatus(document.getParseStatus().name())
                .indexStatus(document.getIndexStatus().name())
                .availabilityStatus(DocumentAvailabilityStatus.from(document).name())
                .segmentCount(document.getSegmentCount())
                .indexedSegmentCount(document.getIndexedSegmentCount())
                .errorCode(document.getErrorCode())
                .errorMessage(document.getErrorMessage())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }

    private static boolean isPreviewAvailable(Asset document) {
        return StringUtils.hasText(document.getPreviewObjectKey())
                || StringUtils.hasText(document.getObjectKey());
    }
}
