package com.anchr.core.settings.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Persisted provider setting metadata.
 */
@Value
@Builder
public class ProviderSetting {
    String id;
    String workspaceId;
    ProviderType providerType;
    String providerName;
    String configValue;
    String secretRef;
    boolean enabled;
    int version;
    String updatedBy;
    LocalDateTime updatedAt;
}
