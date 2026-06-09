package com.anchr.core.settings.domain.repository;

import com.anchr.core.settings.domain.model.ProviderSetting;
import com.anchr.core.settings.domain.model.ProviderType;

import java.util.List;
import java.util.Optional;

/**
 * Repository for provider setting metadata.
 */
public interface ProviderSettingRepository {

    List<ProviderSetting> list(String workspaceId);

    Optional<ProviderSetting> find(String workspaceId, ProviderType providerType, String providerName);

    ProviderSetting upsert(String workspaceId,
                           ProviderType providerType,
                           String providerName,
                           String configValue,
                           String secretRef,
                           boolean enabled,
                           String updatedBy);
}
