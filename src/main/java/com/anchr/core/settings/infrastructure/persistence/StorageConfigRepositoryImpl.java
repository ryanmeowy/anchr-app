package com.anchr.core.settings.infrastructure.persistence;

import com.anchr.core.common.util.IdGen;
import com.anchr.core.settings.domain.model.StorageConfig;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * MyBatis implementation for storage config repository.
 */
@Repository
@RequiredArgsConstructor
public class StorageConfigRepositoryImpl implements StorageConfigRepository {

    private final StorageConfigMapper mapper;

    @Override
    public Optional<StorageConfig> find() {
        return mapper.find().map(this::toDomain);
    }

    @Override
    public StorageConfig upsert(StorageConfig config) {
        mapper.upsert(toRecord(config));
        return config;
    }

    private StorageConfigRecord toRecord(StorageConfig config) {
        StorageConfigRecord r = new StorageConfigRecord();
        r.setId(config.getId());
                r.setEndpoint(config.getEndpoint());
        r.setAccessKeyEnc(config.getAccessKeyEnc());
        r.setSecretKeyEnc(config.getSecretKeyEnc());
        r.setBucket(config.getBucket());
        r.setRegion(config.getRegion());
        r.setPrefix(config.getPrefix());
        r.setRoleArn(config.getRoleArn());
        r.setEnabled(config.isEnabled());
        r.setUpdatedBy(config.getUpdatedBy());
        r.setUpdatedAt(config.getUpdatedAt());
        return r;
    }

    private StorageConfig toDomain(StorageConfigRecord r) {
        return StorageConfig.builder()
                .id(r.getId())
                .endpoint(r.getEndpoint())
                .accessKeyEnc(r.getAccessKeyEnc())
                .secretKeyEnc(r.getSecretKeyEnc())
                .bucket(r.getBucket())
                .region(r.getRegion())
                .prefix(r.getPrefix())
                .roleArn(r.getRoleArn())
                .enabled(r.isEnabled())
                .updatedBy(r.getUpdatedBy())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
