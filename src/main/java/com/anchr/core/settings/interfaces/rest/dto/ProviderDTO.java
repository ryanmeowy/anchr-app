package com.anchr.core.settings.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Safe provider metadata for settings page.
 */
@Value
@Builder
public class ProviderDTO {
    String providerType;
    String providerName;
    boolean enabled;
    boolean available;
    boolean hotSwitchable;
    boolean secretConfigured;
    String maskedApiKey;
    String effectiveStrategy;
    List<String> warnings;
}
