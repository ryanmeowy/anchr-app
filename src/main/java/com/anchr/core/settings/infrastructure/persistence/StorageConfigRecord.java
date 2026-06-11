package com.anchr.core.settings.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * MyBatis record for storage_config.
 */
@Data
public class StorageConfigRecord {
    private Long id;
    private String endpoint;
    private String accessKeyEnc;
    private String secretKeyEnc;
    private String bucket;
    private String region;
    private String prefix;
    private String roleArn;
    private boolean enabled;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
