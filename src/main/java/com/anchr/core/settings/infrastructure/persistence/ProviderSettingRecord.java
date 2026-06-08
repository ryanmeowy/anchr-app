package com.anchr.core.settings.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * MyBatis record for provider_setting.
 */
@Data
public class ProviderSettingRecord {
    private String id;
    private String workspaceId;
    private String providerType;
    private String providerName;
    private String configValue;
    private String secretRef;
    private Boolean enabled;
    private Integer version;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
