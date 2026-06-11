package com.anchr.core.settings.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Available extra_config parameters for a capability.
 */
@Value
@Builder
public class CapabilityParamsDTO {
    List<ParamItem> params;

    @Value
    @Builder
    public static class ParamItem {
        String key;
        String label;
    }
}
