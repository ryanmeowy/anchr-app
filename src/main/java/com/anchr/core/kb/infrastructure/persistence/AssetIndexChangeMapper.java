package com.anchr.core.kb.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for the append-only asset index change log.
 */
@Mapper
public interface AssetIndexChangeMapper {

    int insert(AssetIndexChangeRecord record);

    List<AssetIndexChangeRecord> listAfterRevision(
            @Param("exclusiveRevision") long exclusiveRevision,
            @Param("limit") int limit);
}
