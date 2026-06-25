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

    Optional<KnowledgeBase> findById(String id);

    Optional<KnowledgeBase> findActiveById(String id);

    List<KnowledgeBase> listActiveByIds(List<String> ids);

    List<KnowledgeBase> searchKbs(String q, String status,
                                  LocalDateTime updatedAfter, LocalDateTime updatedBefore,
                                  int limit, int offset);

    long countKbs(String q, String status,
                  LocalDateTime updatedAfter, LocalDateTime updatedBefore);

    boolean updateProfile(String id, String name, String description,
                          String updatedBy, LocalDateTime updatedAt);

    boolean archive(String id, String updatedBy, LocalDateTime updatedAt);

    void refreshDocumentStats(String id, String updatedBy, boolean freshIngest);

    List<KnowledgeBaseStats> findStats(List<String> kbIds);
}
