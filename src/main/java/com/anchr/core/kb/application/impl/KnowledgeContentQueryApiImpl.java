package com.anchr.core.kb.application.impl;

import com.anchr.core.kb.application.api.KnowledgeContentQueryApi;
import com.anchr.core.kb.application.api.model.DocumentSummary;
import com.anchr.core.kb.application.api.model.KnowledgeBaseSummary;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Default read-only Knowledge Content API. */
@Service
@RequiredArgsConstructor
public class KnowledgeContentQueryApiImpl implements KnowledgeContentQueryApi {

    private static final int PAGE_SIZE = 100;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final AssetRepository assetRepository;

    @Override
    public List<KnowledgeBaseSummary> listActiveKnowledgeBases() {
        List<KnowledgeBaseSummary> result = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<KnowledgeBase> page = knowledgeBaseRepository.searchKbs(
                    null, "ACTIVE", null, null, PAGE_SIZE, offset);
            if (page == null || page.isEmpty()) {
                break;
            }
            page.stream().map(this::toSummary).forEach(result::add);
            if (page.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }
        return List.copyOf(result);
    }

    @Override
    public Optional<KnowledgeBaseSummary> findActiveKnowledgeBase(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            return Optional.empty();
        }
        return knowledgeBaseRepository.findActiveById(kbId.trim()).map(this::toSummary);
    }

    @Override
    public Optional<DocumentSummary> findActiveDocument(String kbId, String assetId) {
        if (kbId == null || kbId.isBlank() || assetId == null || assetId.isBlank()) {
            return Optional.empty();
        }
        return assetRepository.findActiveById(kbId.trim(), assetId.trim()).map(this::toSummary);
    }

    @Override
    public List<DocumentSummary> searchActiveDocuments(String kbId, String keyword, int limit) {
        if (kbId == null || kbId.isBlank()
                || keyword == null || keyword.isBlank()
                || limit < 1) {
            return List.of();
        }
        List<Asset> assets = assetRepository.listActive(
                kbId.trim(), keyword.trim(), null, Math.min(limit, PAGE_SIZE), 0);
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }
        return assets.stream().map(this::toSummary).toList();
    }

    @Override
    public Map<String, Long> findActiveIndexGenerations(Collection<String> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> result = assetRepository.findActiveIndexGenerations(assetIds);
        return result == null || result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    private KnowledgeBaseSummary toSummary(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseSummary(
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                knowledgeBase.getStatus() == null ? null : knowledgeBase.getStatus().name());
    }

    private DocumentSummary toSummary(Asset asset) {
        return new DocumentSummary(
                asset.getId(),
                asset.getKbId(),
                asset.getFileName(),
                asset.getTitle(),
                asset.getFileType(),
                asset.getMimeType(),
                asset.getObjectKey(),
                asset.getPreviewObjectKey(),
                asset.getActiveIndexGeneration(),
                asset.getSegmentCount());
    }
}
