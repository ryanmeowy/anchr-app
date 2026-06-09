package com.anchr.core.settings.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Provider switch response.
 */
@Value
@Builder
public class ProviderSwitchResultDTO {
    String providerType;
    String providerName;
    int version;
    boolean effectiveImmediately;
    List<String> warnings;
}
