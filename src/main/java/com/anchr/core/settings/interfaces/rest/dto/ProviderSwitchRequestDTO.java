package com.anchr.core.settings.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Provider switch request.
 */
@Data
public class ProviderSwitchRequestDTO {
    @NotBlank
    private String providerType;

    @NotBlank
    private String providerName;
}
