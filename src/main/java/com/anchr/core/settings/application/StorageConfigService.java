package com.anchr.core.settings.application;

import com.anchr.core.settings.domain.model.StorageConfig;
import com.anchr.core.settings.interfaces.rest.dto.StorageConfigUpdateRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.StorageConnectionTestRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.StorageConnectionTestResultDTO;

import java.util.Optional;

/**
 * Application service for object storage configuration.
 */
public interface StorageConfigService {

    Optional<StorageConfig> get();

    StorageConfig save(StorageConfigUpdateRequestDTO request);

    StorageConnectionTestResultDTO test(StorageConnectionTestRequestDTO request);

    void archive(Long id);

    String maskAccessKey(StorageConfig config);

    String maskSecretKey(StorageConfig config);
}
