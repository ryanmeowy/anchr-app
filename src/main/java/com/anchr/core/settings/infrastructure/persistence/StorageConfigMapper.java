package com.anchr.core.settings.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/**
 * MyBatis mapper for storage_config.
 */
@Mapper
public interface StorageConfigMapper {

    Optional<StorageConfigRecord> find();

    int upsert(StorageConfigRecord record);
}
