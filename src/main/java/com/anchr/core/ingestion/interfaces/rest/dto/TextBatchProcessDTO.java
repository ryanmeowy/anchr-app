package com.anchr.core.ingestion.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Batch request item for text ingestion after frontend direct upload.
 */
@Data
public class TextBatchProcessDTO {

    /**
     * OSS object key.
     */
    @NotBlank(message = "OSS key cannot be empty")
    private String key;

    /**
     * Original file name.
     */
    @NotBlank(message = "fileName cannot be empty")
    private String fileName;

    /**
     * File fingerprint (MD5) provided by frontend.
     */
    @NotBlank(message = "fileHash cannot be empty")
    private String fileHash;

    /**
     * Optional custom title for the asset.
     */
    private String title;

    /**
     * Optional mime type from browser.
     */
    private String mimeType;
}
