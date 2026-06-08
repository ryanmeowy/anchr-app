package com.anchr.core.ingestion.domain.model;

import lombok.Data;

/**
 * Metadata snapshot for one uploaded text asset.
 */
@Data
public class TextAssetMetadata {
    private String kbId;
    private String assetId;
    private String title;
    private String fileName;
    private String mimeType;
    private String objectKey;
    private String fileHash;
    private String sourceUrl;
    private Long createdAt;
    private Long updatedAt;
}
