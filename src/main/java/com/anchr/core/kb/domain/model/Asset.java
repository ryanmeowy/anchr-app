package com.anchr.core.kb.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * asset metadata inside a knowledge base.
 */
@Value
@Builder(toBuilder = true)
public class Asset {

    String id;
    String kbId;
    String fileName;
    String title;
    String fileType;
    String mimeType;
    Long sizeBytes;
    String fileHash;
    String versionGroupId;
    Integer versionNo;
    String objectKey;
    String previewObjectKey;
    DocumentParseStatus parseStatus;
    DocumentIndexStatus indexStatus;
    int segmentCount;
    int indexedSegmentCount;
    long activeIndexGeneration;
    String errorCode;
    String errorMessage;
    String createdBy;
    String updatedBy;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    LocalDateTime deletedAt;
}
