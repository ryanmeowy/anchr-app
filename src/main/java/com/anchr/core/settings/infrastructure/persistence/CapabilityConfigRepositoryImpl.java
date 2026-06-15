package com.anchr.core.settings.infrastructure.persistence;

import com.anchr.core.common.util.IdGen;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MyBatis implementation for capability config repository.
 */
@Repository
@RequiredArgsConstructor
public class CapabilityConfigRepositoryImpl implements CapabilityConfigRepository {

    private final CapabilityConfigMapper mapper;

    @Override
    public List<CapabilityConfig> findByCapability(String capability) {
        return mapper.findByCapability(capability).stream().map(this::toDomain).toList();
    }

    @Override
    public CapabilityConfig upsert(CapabilityConfig config) {
        CapabilityConfigRecord record = toRecord(config);
        mapper.upsert(record);
        return toDomain(record);
    }

    @Override
    public void del(String capability, Long id) {
        mapper.del(capability, id);
    }

    private CapabilityConfigRecord toRecord(CapabilityConfig config) {
        CapabilityConfigRecord record = new CapabilityConfigRecord();
        record.setId(config.getId());
        record.setCapability(config.getCapability());
        record.setBaseUrl(config.getBaseUrl());
        record.setApiKeyEnc(config.getApiKeyEnc());
        record.setModelName(config.getModelName());
        record.setExtraConfig(config.getExtraConfig());
        record.setEnabled(config.isEnabled());
        record.setUpdatedBy(config.getUpdatedBy());
        record.setUpdatedAt(config.getUpdatedAt());
        return record;
    }

    private CapabilityConfig toDomain(CapabilityConfigRecord record) {
        return CapabilityConfig.builder()
                .id(record.getId())
                .capability(record.getCapability())
                .baseUrl(record.getBaseUrl())
                .apiKeyEnc(record.getApiKeyEnc())
                .modelName(record.getModelName())
                .extraConfig(record.getExtraConfig())
                .enabled(record.isEnabled())
                .updatedBy(record.getUpdatedBy())
                .updatedAt(record.getUpdatedAt())
                .build();
    }
}
