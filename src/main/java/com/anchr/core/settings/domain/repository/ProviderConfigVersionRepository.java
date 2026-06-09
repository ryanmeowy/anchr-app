package com.anchr.core.settings.domain.repository;

/**
 * Repository for provider configuration version snapshots.
 */
public interface ProviderConfigVersionRepository {

    void save(String providerSettingId, int version, String configSnapshot, String createdBy);
}
