package com.anchr.core.settings.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Configuration for a capability (embedding, generation, rerank).
 */
@Value
@Builder
public class CapabilityConfig {

    Long id;
    String capability;
    String baseUrl;
    String apiKeyEnc;
    String modelName;
    String extraConfig;
    boolean enabled;
    String updatedBy;
    LocalDateTime updatedAt;
}
