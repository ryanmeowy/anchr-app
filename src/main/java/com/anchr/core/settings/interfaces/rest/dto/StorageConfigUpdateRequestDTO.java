package com.anchr.core.settings.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for updating storage config.
 * accessKey / secretKey can be empty to keep existing values.
 */
@Data
public class StorageConfigUpdateRequestDTO {

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
    private String prefix;

    @Size(max = 256)
    private String roleArn;
}
