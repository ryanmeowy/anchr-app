package com.anchr.core.kb.application;

import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseStats;

import java.util.List;

/**
 * Application service for knowledge base product APIs.
 */
public interface KnowledgeBaseService {

    KnowledgeBase create(String name, String description);

    List<KnowledgeBase> search(String query, int limit);

    PagedResult<KnowledgeBase> list(int page, int size);

    KnowledgeBase get(String kbId);

    KnowledgeBase update(String kbId, String name, String description);

    void archive(String kbId);

    KnowledgeBaseStats getStats(String kbId);

    PagedResult<Asset> listDocuments(String kbId, int page, int size);

    Asset getDocument(String kbId, String assetId);

    void deleteDocument(String kbId, String assetId);

    record PagedResult<T>(List<T> items, long total, int page, int size) {
    }
}
