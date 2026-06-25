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

    Optional<KnowledgeBaseRecord> findActiveById(@Param("id") String id);

    Optional<KnowledgeBaseRecord> findById(@Param("id") String id);

    List<KnowledgeBaseRecord> listActiveByIds(@Param("ids") List<String> ids);

    List<KnowledgeBaseRecord> searchKbs(@Param("q") String q,
                                        @Param("status") String status,
                                        @Param("updatedAfter") LocalDateTime updatedAfter,
                                        @Param("updatedBefore") LocalDateTime updatedBefore,
                                        @Param("limit") int limit,
                                        @Param("offset") int offset);

    long countKbs(@Param("q") String q,
                  @Param("status") String status,
                  @Param("updatedAfter") LocalDateTime updatedAfter,
                  @Param("updatedBefore") LocalDateTime updatedBefore);

    int updateProfile(@Param("id") String id,
                      @Param("name") String name,
                      @Param("description") String description,
                      @Param("updatedBy") String updatedBy,
                      @Param("updatedAt") LocalDateTime updatedAt);

    int archive(@Param("id") String id,
                @Param("updatedBy") String updatedBy,
                @Param("updatedAt") LocalDateTime updatedAt);

    int refreshDocumentStats(@Param("id") String id,
                             @Param("updatedBy") String updatedBy,
                             @Param("updatedAt") LocalDateTime updatedAt,
                             @Param("lastIngestedAt") LocalDateTime lastIngestedAt);

    List<KnowledgeBaseStatsRecord> findStats(@Param("kbIds") List<String> kbIds);
}
