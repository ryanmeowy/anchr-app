package com.anchr.core.kb.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper for document_asset persistence.
 */
@Mapper
public interface AssetMapper {

    int insert(AssetRecord record);

    Optional<AssetRecord> findActiveById(@Param("kbId") String kbId,
                                                  @Param("assetId") String assetId);

    List<AssetRecord> listActive(@Param("kbId") String kbId,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    long countActive(@Param("kbId") String kbId);

    Optional<AssetRecord> findActiveByHash(@Param("kbId") String kbId,
                                                    @Param("fileHash") String fileHash);

    int updateStatuses(@Param("kbId") String kbId,
                       @Param("assetId") String assetId,
                       @Param("parseStatus") String parseStatus,
                       @Param("indexStatus") String indexStatus,
                       @Param("updatedBy") String updatedBy,
                       @Param("updatedAt") LocalDateTime updatedAt);

    int updateIngestionResult(@Param("kbId") String kbId,
                              @Param("assetId") String assetId,
                              @Param("parseStatus") String parseStatus,
                              @Param("indexStatus") String indexStatus,
                              @Param("segmentCount") int segmentCount,
                              @Param("errorCode") String errorCode,
                              @Param("errorMessage") String errorMessage,
                              @Param("updatedBy") String updatedBy,
                              @Param("updatedAt") LocalDateTime updatedAt);

    int markDeleted(@Param("kbId") String kbId,
                    @Param("assetId") String assetId,
                    @Param("updatedBy") String updatedBy,
                    @Param("updatedAt") LocalDateTime updatedAt);
}
