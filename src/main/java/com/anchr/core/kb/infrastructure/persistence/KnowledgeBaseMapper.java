package com.anchr.core.kb.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper for knowledge_base persistence.
 */
@Mapper
public interface KnowledgeBaseMapper {

    int insert(KnowledgeBaseRecord record);

    Optional<KnowledgeBaseRecord> findActiveById(@Param("workspaceId") String workspaceId,
                                                @Param("id") String id);

    Optional<KnowledgeBaseRecord> findById(@Param("workspaceId") String workspaceId,
                                           @Param("id") String id);

    List<KnowledgeBaseRecord> listActiveByIds(@Param("workspaceId") String workspaceId,
                                              @Param("ids") List<String> ids);

    List<KnowledgeBaseRecord> listActive(@Param("workspaceId") String workspaceId,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    long countActive(@Param("workspaceId") String workspaceId);

    int updateProfile(@Param("workspaceId") String workspaceId,
                      @Param("id") String id,
                      @Param("name") String name,
                      @Param("description") String description,
                      @Param("updatedBy") String updatedBy,
                      @Param("updatedAt") LocalDateTime updatedAt);

    int archive(@Param("workspaceId") String workspaceId,
                @Param("id") String id,
                @Param("updatedBy") String updatedBy,
                @Param("updatedAt") LocalDateTime updatedAt);

    int refreshDocumentStats(@Param("workspaceId") String workspaceId,
                             @Param("id") String id,
                             @Param("updatedBy") String updatedBy,
                             @Param("updatedAt") LocalDateTime updatedAt);

    Optional<KnowledgeBaseStatsRecord> findStats(@Param("workspaceId") String workspaceId,
                                                 @Param("id") String id);
}
