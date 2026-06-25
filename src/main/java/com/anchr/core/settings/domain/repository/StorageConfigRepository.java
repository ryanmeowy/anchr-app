package com.anchr.core.settings.domain.repository;

import com.anchr.core.settings.domain.model.StorageConfig;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Repository for object storage configuration.
 */
public interface StorageConfigRepository {

    Optional<StorageConfig> find();

    StorageConfig upsert(StorageConfig config);

    boolean archive(Long id, String updatedBy, LocalDateTime updatedAt);
}
