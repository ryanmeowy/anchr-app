package com.anchr.core.settings.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * MyBatis mapper for app_setting.
 */
@Mapper
public interface AppSettingMapper {

    Optional<AppSettingRecord> find(@Param("settingKey") String settingKey);

    int upsert(@Param("id") String id,
               @Param("settingKey") String settingKey,
               @Param("settingValue") String settingValue,
               @Param("updatedBy") String updatedBy,
               @Param("updatedAt") LocalDateTime updatedAt);
}
