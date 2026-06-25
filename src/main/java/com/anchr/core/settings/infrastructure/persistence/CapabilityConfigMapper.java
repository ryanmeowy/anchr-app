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

    List<CapabilityConfigRecord> findAllByCapability(@Param("capability") String capability);

    int insert(CapabilityConfigRecord record);

    int update(CapabilityConfigRecord record);

    int select(@Param("capability") String capability, @Param("id") Long id);

    int disableAll(@Param("capability") String capability);

    void del(@Param("capability") String capability, @Param("id") Long id);

    CapabilityConfigRecord findById(@Param("id") Long id);
}
