package com.anchr.core.settings.infrastructure.persistence;

import com.anchr.core.common.infrastructure.id.PrefixedIdGenerator;
import com.anchr.core.settings.domain.model.AppSetting;
import com.anchr.core.settings.domain.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * MyBatis implementation of application settings repository.
 */
@Repository
@RequiredArgsConstructor
public class MyBatisAppSettingRepository implements AppSettingRepository {

    private final AppSettingMapper mapper;
    private final PrefixedIdGenerator idGenerator;

    @Override
    public Optional<AppSetting> find(String workspaceId, String settingKey) {
        return mapper.find(workspaceId, settingKey).map(this::toDomain);
    }

    @Override
    public AppSetting upsert(String workspaceId, String settingKey, String settingValue, String updatedBy) {
        mapper.upsert(idGenerator.nextId("set"), workspaceId, settingKey, settingValue, updatedBy, LocalDateTime.now());
        return find(workspaceId, settingKey).orElseThrow();
    }

    private AppSetting toDomain(AppSettingRecord record) {
        return AppSetting.builder()
                .id(record.getId())
                .workspaceId(record.getWorkspaceId())
                .settingKey(record.getSettingKey())
                .settingValue(record.getSettingValue())
                .version(record.getVersion() == null ? 1 : record.getVersion())
                .updatedBy(record.getUpdatedBy())
                .updatedAt(record.getUpdatedAt())
                .build();
    }
}
