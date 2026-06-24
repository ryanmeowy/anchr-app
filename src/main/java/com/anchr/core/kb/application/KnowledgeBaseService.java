package com.anchr.core.kb.application;

import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseHealth;
import com.anchr.core.kb.domain.model.KnowledgeBaseStats;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Application service for knowledge base product APIs.
 */
public interface KnowledgeBaseService {

    KnowledgeBase create(String name, String description);

    PagedResult<KnowledgeBase> listKbs(String q, String status,
                                       LocalDateTime updatedAfter, LocalDateTime updatedBefore,
                                       Integer page, Integer size);

    KnowledgeBase get(String kbId);

    KnowledgeBase update(String kbId, String name, String description);

    void archive(String kbId);

    List<KnowledgeBaseStats> getStats(List<String> kbIds);

    KnowledgeBaseHealth getHealth(String kbId);

    PagedResult<Asset> listDocuments(String kbId, Integer page, Integer size);

    Asset getDocument(String kbId, String assetId);

    void deleteDocument(String kbId, String assetId);

    record PagedResult<T>(List<T> items, long total, int page, int size) {
    }
}
