package com.anchr.core.kb.interfaces.rest.dto;

import com.anchr.core.kb.domain.model.DocumentAsset;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Document asset response DTO.
 */
@Value
@Builder
public class DocumentAssetDTO {

    String id;
    String kbId;
    String fileName;
    String title;
    String fileType;
    String mimeType;
    Long sizeBytes;
    String fileHash;
    String previewObjectKey;
    String thumbnailKey;
    String sourceUrl;
    String parseStatus;
    String indexStatus;
    int segmentCount;
    String embeddingProfile;
    String errorCode;
    String errorMessage;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static DocumentAssetDTO from(DocumentAsset document) {
        return DocumentAssetDTO.builder()
                .id(document.getId())
                .kbId(document.getKbId())
                .fileName(document.getFileName())
                .title(document.getTitle())
                .fileType(document.getFileType())
                .mimeType(document.getMimeType())
                .sizeBytes(document.getSizeBytes())
                .fileHash(document.getFileHash())
                .previewObjectKey(document.getPreviewObjectKey())
                .thumbnailKey(document.getThumbnailKey())
                .sourceUrl(document.getSourceUrl())
                .parseStatus(document.getParseStatus().name())
                .indexStatus(document.getIndexStatus().name())
                .segmentCount(document.getSegmentCount())
                .embeddingProfile(document.getEmbeddingProfile())
                .errorCode(document.getErrorCode())
                .errorMessage(document.getErrorMessage())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }
}
