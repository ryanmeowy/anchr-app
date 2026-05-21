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
public interface DocumentAssetMapper {

    Optional<DocumentAssetRecord> findActiveById(@Param("workspaceId") String workspaceId,
                                                 @Param("kbId") String kbId,
                                                 @Param("assetId") String assetId);

    List<DocumentAssetRecord> listActive(@Param("workspaceId") String workspaceId,
                                         @Param("kbId") String kbId,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    long countActive(@Param("workspaceId") String workspaceId,
                     @Param("kbId") String kbId);

    Optional<DocumentAssetRecord> findActiveByHash(@Param("workspaceId") String workspaceId,
                                                   @Param("kbId") String kbId,
                                                   @Param("fileHash") String fileHash);

    int markDeleted(@Param("workspaceId") String workspaceId,
                    @Param("kbId") String kbId,
                    @Param("assetId") String assetId,
                    @Param("updatedBy") String updatedBy,
                    @Param("updatedAt") LocalDateTime updatedAt);
}
