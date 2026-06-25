package com.anchr.core.settings.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for testing a capability connection.
 * Does not persist anything.
 */
@Data
public class CapabilityConnectionTestRequestDTO {

    @NotBlank
    @Size(max = 32)
    private String capability;

    @NotBlank
    @Size(max = 512)
    private String baseUrl;

    @Size(max = 256)
    private String apiKey;

    @Size(max = 128)
    private String modelName;

    /** When set, use the stored key of this config instead of {@link #apiKey}. */
    private Long configId;
}
