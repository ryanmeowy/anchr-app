package com.anchr.core.settings.domain.repository;

import com.anchr.core.settings.domain.model.StorageConfig;

import java.util.Optional;

/**
 * Repository for object storage configuration.
 */
public interface StorageConfigRepository {

    Optional<StorageConfig> find();

    StorageConfig upsert(StorageConfig config);
}
