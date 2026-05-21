package com.anchr.core.kb.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Document asset metadata inside a knowledge base.
 */
@Value
@Builder(toBuilder = true)
public class DocumentAsset {

    String id;
    String workspaceId;
    String kbId;
    String fileName;
    String title;
    String fileType;
    String mimeType;
    Long sizeBytes;
    String fileHash;
    String objectKey;
    String previewObjectKey;
    String thumbnailKey;
    String sourceUrl;
    DocumentParseStatus parseStatus;
    DocumentIndexStatus indexStatus;
    int segmentCount;
    String embeddingProfile;
    String errorCode;
    String errorMessage;
    String createdBy;
    String updatedBy;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    LocalDateTime deletedAt;
}
