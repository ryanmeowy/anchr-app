package com.anchr.core.ingestion.interfaces.rest.dto;

import lombok.Data;

/**
 * Batch request item for text ingestion after frontend direct upload.
 */
@Data
public class TextBatchProcessDTO {

    /**
     * OSS object key.
     */
    private String key;

    /**
     * Original file name.
     */
    private String fileName;

    /**
     * File fingerprint (MD5) provided by frontend.
     */
    private String fileHash;

    /**
     * Optional custom title for the asset.
     */
    private String title;

    /**
     * Optional mime type from browser.
     */
    private String mimeType;

    /**
     * Optional source URL for URL/HTML ingestion.
     */
    private String sourceUrl;
}
