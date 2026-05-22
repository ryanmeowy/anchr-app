package com.anchr.core.settings.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Provider connection test result.
 */
@Value
@Builder
public class ProviderConnectionTestResultDTO {
    String providerType;
    String providerName;
    boolean success;
    long latencyMs;
    String code;
    String message;
}
