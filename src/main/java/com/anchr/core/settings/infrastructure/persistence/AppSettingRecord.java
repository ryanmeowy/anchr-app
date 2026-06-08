package com.anchr.core.settings.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * MyBatis record for app_setting.
 */
@Data
public class AppSettingRecord {
    private String id;
    private String workspaceId;
    private String settingKey;
    private String settingValue;
    private Integer version;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
