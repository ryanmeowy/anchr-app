package com.anchr.core.kb.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Whole-document preview response. Citation and surrounding-segment context intentionally do not belong here.
 */
@Value
@Builder
public class AssetPreviewDTO {

    String assetId;
    String kbId;
    String kbName;
    String fileName;
    String title;
    String fileType;
    String mimeType;
    Long sizeBytes;
    Integer versionNo;
    LocalDateTime createdAt;
    String parseStatus;
    String indexStatus;
    int segmentCount;
    String previewType;
    String previewUrl;
    Long expiresAt;
}
