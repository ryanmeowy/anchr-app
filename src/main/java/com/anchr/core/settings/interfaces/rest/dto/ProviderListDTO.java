package com.anchr.core.settings.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Provider list response.
 */
@Value
@Builder
public class ProviderListDTO {
    List<ProviderDTO> providers;
}
