package com.anchr.core.settings.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

/**
 * MyBatis mapper for capability_config.
 */
@Mapper
public interface CapabilityConfigMapper {

    Optional<CapabilityConfigRecord> findByCapability(@Param("capability") String capability);

    int upsert(CapabilityConfigRecord record);
}
