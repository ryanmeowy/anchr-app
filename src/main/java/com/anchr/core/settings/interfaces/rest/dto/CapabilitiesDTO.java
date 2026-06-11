package com.anchr.core.settings.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Settings capability overview.
 */
@Value
@Builder
public class CapabilitiesDTO {
    CapabilityDTO generation;
    CapabilityDTO embedding;
    CapabilityDTO rerank;
    CapabilityDTO ocr;
    CapabilityDTO objectStorage;
}
