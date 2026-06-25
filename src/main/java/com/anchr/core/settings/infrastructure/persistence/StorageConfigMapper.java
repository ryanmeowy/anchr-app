package com.anchr.core.settings.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * MyBatis mapper for storage_config.
 */
@Mapper
public interface StorageConfigMapper {

    Optional<StorageConfigRecord> find();

    int upsert(StorageConfigRecord record);

    int archive(@Param("id") Long id,
                @Param("updatedBy") String updatedBy,
                @Param("updatedAt") LocalDateTime updatedAt);
}
