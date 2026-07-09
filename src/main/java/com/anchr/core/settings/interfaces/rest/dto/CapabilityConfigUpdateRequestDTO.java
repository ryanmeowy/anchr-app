package com.anchr.core.settings.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * Request body for updating a capability config.
 * apiKey can be empty to keep the existing key.
 */
@Data
public class CapabilityConfigUpdateRequestDTO {

    @NotBlank
    @Size(max = 512)
    private String baseUrl;

    @Size(max = 256)
    private String apiKey;

    @Size(max = 128)
    private String modelName;

    private Map<String, Object> extraConfig;
}
