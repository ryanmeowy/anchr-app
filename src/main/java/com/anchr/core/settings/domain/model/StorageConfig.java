package com.anchr.core.settings.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Object storage configuration.
 */
@Value
@Builder
public class StorageConfig {
    Long id;
    String endpoint;
    String accessKeyEnc;
    String secretKeyEnc;
    String bucket;
    String region;
    String prefix;
    String roleArn;
    boolean enabled;
    String updatedBy;
    LocalDateTime updatedAt;
}
