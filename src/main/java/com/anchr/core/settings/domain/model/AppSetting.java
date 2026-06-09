package com.anchr.core.settings.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Persisted application setting.
 */
@Value
@Builder
public class AppSetting {
    String id;
    String workspaceId;
    String settingKey;
    String settingValue;
    int version;
    String updatedBy;
    LocalDateTime updatedAt;
}
