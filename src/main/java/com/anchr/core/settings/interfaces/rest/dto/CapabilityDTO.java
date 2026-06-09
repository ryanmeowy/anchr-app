package com.anchr.core.settings.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Single capability status.
 */
@Value
@Builder
public class CapabilityDTO {
    boolean enabled;
    String provider;
    String model;
    Integer dimension;
    String reason;
}
