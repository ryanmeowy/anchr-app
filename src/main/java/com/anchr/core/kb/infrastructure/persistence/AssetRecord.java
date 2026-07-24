package com.anchr.core.kb.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence record for the asset table.
 */
@Data
public class AssetRecord {

    private String id;
    private String kbId;
    private String fileName;
    private String title;
    private String fileType;
    private String mimeType;
    private Long sizeBytes;
    private String fileHash;
    private String versionGroupId;
    private Integer versionNo;
    private String previousAssetId;
    private String objectKey;
    private String previewObjectKey;
    private String thumbnailKey;
    private String sourceUrl;
    private String parseStatus;
    private String indexStatus;
    private Integer segmentCount;
    private Integer indexedSegmentCount;
    private Long activeIndexGeneration;
    private String embeddingProfile;
    private String errorCode;
    private String errorMessage;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
