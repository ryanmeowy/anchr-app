package com.anchr.core.kb.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

/**
 * MyBatis baseline mapper for Phase 4 knowledge_base persistence.
 */
@Mapper
public interface KnowledgeBaseMapper {

    int insert(KnowledgeBaseRecord record);

    Optional<KnowledgeBaseRecord> findActiveById(@Param("workspaceId") String workspaceId,
                                                @Param("id") String id);

    int updateName(@Param("workspaceId") String workspaceId,
                   @Param("id") String id,
                   @Param("name") String name,
                   @Param("updatedBy") String updatedBy,
                   @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
