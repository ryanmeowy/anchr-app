package com.anchr.core.settings.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for capability_config.
 */
@Mapper
public interface CapabilityConfigMapper {

    List<CapabilityConfigRecord> findByCapability(@Param("capability") String capability);

    int upsert(CapabilityConfigRecord record);

    void del(@Param("capability")String capability, @Param("id") Long id);
}
