package com.anchr.core.settings.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper for provider_setting.
 */
@Mapper
public interface ProviderSettingMapper {

    List<ProviderSettingRecord> list(@Param("workspaceId") String workspaceId);

    Optional<ProviderSettingRecord> find(@Param("workspaceId") String workspaceId,
                                         @Param("providerType") String providerType,
                                         @Param("providerName") String providerName);

    int upsert(@Param("id") String id,
               @Param("workspaceId") String workspaceId,
               @Param("providerType") String providerType,
               @Param("providerName") String providerName,
               @Param("configValue") String configValue,
               @Param("secretRef") String secretRef,
               @Param("enabled") boolean enabled,
               @Param("updatedBy") String updatedBy,
               @Param("updatedAt") LocalDateTime updatedAt);

    int insertVersion(@Param("id") String id,
                      @Param("providerSettingId") String providerSettingId,
                      @Param("version") int version,
                      @Param("configSnapshot") String configSnapshot,
                      @Param("createdBy") String createdBy,
                      @Param("createdAt") LocalDateTime createdAt);
}
