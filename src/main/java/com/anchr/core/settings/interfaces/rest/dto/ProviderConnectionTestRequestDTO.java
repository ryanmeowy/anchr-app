package com.anchr.core.settings.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Provider connection test request.
 */
@Data
public class ProviderConnectionTestRequestDTO {
    @NotBlank
    private String providerType;

    @NotBlank
    private String providerName;
}
