package com.anchr.core.ingestion.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request item for creating an ingestion task.
 */
@Data
public class IngestionTaskCreateItemDTO {

    @NotBlank
    @Size(max = 512)
    private String fileName;

    @Size(max = 512)
    private String title;

    @NotBlank
    @Size(max = 32)
    private String fileType;

    @Size(max = 128)
    private String mimeType;

    private Long sizeBytes;

    @Size(max = 1024)
    private String objectKey;

    @Size(max = 128)
    private String fileHash;

    private String sourceUrl;
}
