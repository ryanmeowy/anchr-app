package com.anchr.core.kb.domain.repository;

import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseStats;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository boundary for knowledge base persistence.
 */
public interface KnowledgeBaseRepository {

    void save(KnowledgeBase knowledgeBase);

    Optional<KnowledgeBase> findById(String workspaceId, String id);

    Optional<KnowledgeBase> findActiveById(String workspaceId, String id);

    List<KnowledgeBase> listActive(String workspaceId, int limit, int offset);

    long countActive(String workspaceId);

    boolean updateProfile(String workspaceId, String id, String name, String description,
                          String updatedBy, LocalDateTime updatedAt);

    boolean archive(String workspaceId, String id, String updatedBy, LocalDateTime updatedAt);

    void refreshDocumentStats(String workspaceId, String id, String updatedBy, LocalDateTime updatedAt);

    Optional<KnowledgeBaseStats> findStats(String workspaceId, String id);
}
