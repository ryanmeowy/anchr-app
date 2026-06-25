package com.anchr.core.settings.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Result of a capability connection test.
 */
@Value
@Builder
public class CapabilityConnectionTestResultDTO {
    boolean success;
    long latencyMs;
    String message;
    Integer dimension;
}
