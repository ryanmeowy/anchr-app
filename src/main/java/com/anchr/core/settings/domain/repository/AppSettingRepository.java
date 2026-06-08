package com.anchr.core.settings.domain.repository;

import com.anchr.core.settings.domain.model.AppSetting;

import java.util.Optional;

/**
 * Repository for non-secret application settings.
 */
public interface AppSettingRepository {

    Optional<AppSetting> find(String workspaceId, String settingKey);

    AppSetting upsert(String workspaceId, String settingKey, String settingValue, String updatedBy);
}
