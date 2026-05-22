package com.anchr.core.settings.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Appearance preference response.
 */
@Value
@Builder
public class PreferenceDTO {
    String theme;
}
