package com.anchr.core.settings.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for testing a storage connection.
 * Does not persist anything.
 */
@Data
public class StorageConnectionTestRequestDTO {

    @NotBlank
    @Size(max = 512)
    private String endpoint;

    @Size(max = 256)
    private String accessKey;

    @Size(max = 256)
    private String secretKey;

    @NotBlank
    @Size(max = 256)
    private String bucket;

    @Size(max = 64)
    private String region;

    @Size(max = 256)
    private String roleArn;

    /** When set, use the stored credentials of this config instead of {@link #accessKey} / {@link #secretKey}. */
    private Long configId;
}
