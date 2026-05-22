package com.anchr.core.settings.infrastructure.persistence;

import com.anchr.core.common.infrastructure.id.PrefixedIdGenerator;
import com.anchr.core.settings.domain.model.ProviderSetting;
import com.anchr.core.settings.domain.model.ProviderType;
import com.anchr.core.settings.domain.repository.ProviderConfigVersionRepository;
import com.anchr.core.settings.domain.repository.ProviderSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis implementation of provider settings repository.
 */
@Repository
@RequiredArgsConstructor
public class MyBatisProviderSettingRepository implements ProviderSettingRepository, ProviderConfigVersionRepository {

    private final ProviderSettingMapper mapper;
    private final PrefixedIdGenerator idGenerator;

    @Override
    public List<ProviderSetting> list(String workspaceId) {
        return mapper.list(workspaceId).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<ProviderSetting> find(String workspaceId, ProviderType providerType, String providerName) {
        return mapper.find(workspaceId, providerType.name(), providerName).map(this::toDomain);
    }

    @Override
    public ProviderSetting upsert(String workspaceId,
                                  ProviderType providerType,
                                  String providerName,
                                  String configValue,
                                  String secretRef,
                                  boolean enabled,
                                  String updatedBy) {
        mapper.upsert(idGenerator.nextId("provs"), workspaceId, providerType.name(), providerName, configValue,
                secretRef, enabled, updatedBy, LocalDateTime.now());
        return find(workspaceId, providerType, providerName).orElseThrow();
    }

    @Override
    public void save(String providerSettingId, int version, String configSnapshot, String createdBy) {
        mapper.insertVersion(idGenerator.nextId("provcfg"), providerSettingId, version, configSnapshot,
                createdBy, LocalDateTime.now());
    }

    private ProviderSetting toDomain(ProviderSettingRecord record) {
        return ProviderSetting.builder()
                .id(record.getId())
                .workspaceId(record.getWorkspaceId())
                .providerType(ProviderType.parse(record.getProviderType()))
                .providerName(record.getProviderName())
                .configValue(record.getConfigValue())
                .secretRef(record.getSecretRef())
                .enabled(Boolean.TRUE.equals(record.getEnabled()))
                .version(record.getVersion() == null ? 1 : record.getVersion())
                .updatedBy(record.getUpdatedBy())
                .updatedAt(record.getUpdatedAt())
                .build();
    }
}
