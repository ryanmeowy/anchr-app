package com.anchr.core.settings.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RuntimeConfigMapper {

    List<RuntimeConfigRecord> findByType(@Param("type") String type);

    int upsertAll(@Param("entries") List<RuntimeConfigRecord> entries);
}
