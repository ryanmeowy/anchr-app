package com.anchr.core.settings.infrastructure.persistence;

import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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
    public List<CapabilityConfig> findAllByCapability(String capability) {
        return mapper.findAllByCapability(capability).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<CapabilityConfig> findById(Long id) {
        return Optional.ofNullable(mapper.findById(id)).map(this::toDomain);
    }

    @Override
    public CapabilityConfig insert(CapabilityConfig config) {
        mapper.insert(toRecord(config));
        return config;
    }

    @Override
    public CapabilityConfig update(CapabilityConfig config) {
        mapper.update(toRecord(config));
        return config;
    }

    @Override
    public void select(String capability, Long id) {
        mapper.select(capability, id);
    }

    @Override
    public void disableAll(String capability) {
        mapper.disableAll(capability);
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
