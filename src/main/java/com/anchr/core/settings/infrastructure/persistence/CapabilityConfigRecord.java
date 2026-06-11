package com.anchr.core.settings.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * MyBatis record for capability_config.
 */
@Data
public class CapabilityConfigRecord {
    private Long id;
    private String capability;
    private String baseUrl;
    private String apiKeyEnc;
    private String modelName;
    private String extraConfig;
    private boolean enabled;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
