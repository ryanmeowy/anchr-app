package com.anchr.core.ingestion.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Batch Process Request Data Transfer Object
 * Represents a request for batch processing of a single image item in the system.
 * This DTO contains the necessary information to locate and process an image
 * that has been uploaded to Alibaba Cloud OSS.
 *
 * @author Ryan
 * @since 2025/12/18
 */
@Data
public class BatchProcessDTO {

    /**
     * OSS Object Key
     * example: images/2024/abc.jpg
     */
    @NotBlank(message = "OSS key cannot be empty")
    private String key;

    /**
     * original file name
     */
    @NotBlank(message = "fileName cannot be empty")
    private String fileName;

    /**
     * File fingerprint (MD5)
     */
    @NotBlank(message = "fileHash cannot be empty")
    private String fileHash;
}