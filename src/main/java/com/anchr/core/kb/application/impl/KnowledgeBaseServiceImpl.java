package com.anchr.core.kb.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.domain.model.DocumentAsset;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseStats;
import com.anchr.core.kb.domain.model.KnowledgeBaseStatus;
import com.anchr.core.kb.domain.repository.DocumentAssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.search.domain.repository.KbSegmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Default knowledge base application service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentAssetRepository documentAssetRepository;
    private final IdGen idGen;
    private final KbSegmentRepository kbSegmentRepository;

    @Override
    @Transactional
    public KnowledgeBase create(String name, String description) {
        String normalizedName = requireName(name);
        RequestUserContext context = UserContextHolder.get();
        LocalDateTime now = LocalDateTime.now();
        KnowledgeBase knowledgeBase = KnowledgeBase.builder()
                .id(idGen.nextIdStr())
                .name(normalizedName)
                .description(trimToNull(description))
                .status(KnowledgeBaseStatus.ACTIVE)
                .documentCount(0)
                .segmentCount(0)
                .createdBy(context.userId())
                .updatedBy(context.userId())
                .createdAt(now)
                .updatedAt(now)
                .build();
        knowledgeBaseRepository.save(knowledgeBase);
        return knowledgeBase;
    }

    private static final int DEFAULT_SEARCH_LIMIT = 20;
    private static final int MAX_SEARCH_LIMIT = 50;

    @Override
    public List<KnowledgeBase> search(String query, int limit) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        String trimmed = query.trim();
        int boundedLimit = limit <= 0 ? DEFAULT_SEARCH_LIMIT : Math.min(limit, MAX_SEARCH_LIMIT);
        return knowledgeBaseRepository.searchActive(trimmed, boundedLimit);
    }

    @Override
    public PagedResult<KnowledgeBase> list(int page, int size) {
        PageBounds bounds = normalizePage(page, size);
        return new PagedResult<>(
                knowledgeBaseRepository.listActive(bounds.size(), bounds.offset()),
                knowledgeBaseRepository.countActive(),
                bounds.page(),
                bounds.size());
    }

    @Override
    public KnowledgeBase get(String kbId) {
        return knowledgeBaseRepository.findActiveById(requireId(kbId, "kbId"))
                .orElseThrow(() -> new BusinessException(ApiError.KNOWLEDGE_BASE_NOT_FOUND));
    }

    @Override
    @Transactional
    public KnowledgeBase update(String kbId, String name, String description) {
        RequestUserContext context = UserContextHolder.get();
        String id = requireId(kbId, "kbId");
        String normalizedName = requireName(name);
        LocalDateTime now = LocalDateTime.now();
        boolean updated = knowledgeBaseRepository.updateProfile(
                id, normalizedName, trimToNull(description), context.userId(), now);
        if (!updated) {
            throw new BusinessException(ApiError.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return get(id);
    }

    @Override
    @Transactional
    public void archive(String kbId) {
        RequestUserContext context = UserContextHolder.get();
        boolean archived = knowledgeBaseRepository.archive(
                requireId(kbId, "kbId"), context.userId(), LocalDateTime.now());
        if (!archived) {
            throw new BusinessException(ApiError.KNOWLEDGE_BASE_NOT_FOUND);
        }
    }

    @Override
    public KnowledgeBaseStats getStats(String kbId) {
        return knowledgeBaseRepository.findStats(requireId(kbId, "kbId"))
                .orElseThrow(() -> new BusinessException(ApiError.KNOWLEDGE_BASE_NOT_FOUND));
    }

    @Override
    public PagedResult<DocumentAsset> listDocuments(String kbId, int page, int size) {
        String id = requireId(kbId, "kbId");
        get(id);
        PageBounds bounds = normalizePage(page, size);
        return new PagedResult<>(
                documentAssetRepository.listActive(id, bounds.size(), bounds.offset()),
                documentAssetRepository.countActive(id),
                bounds.page(),
                bounds.size());
    }

    @Override
    public DocumentAsset getDocument(String kbId, String assetId) {
        String id = requireId(kbId, "kbId");
        RequestUserContext context = UserContextHolder.get();
        get(id);
        return documentAssetRepository.findActiveById(id, requireId(assetId, "assetId"))
                .orElseThrow(() -> new BusinessException(ApiError.DOCUMENT_NOT_FOUND));
    }

    @Override
    @Transactional
    public void deleteDocument(String kbId, String assetId) {
        String id = requireId(kbId, "kbId");
        String documentId = requireId(assetId, "assetId");
        RequestUserContext context = UserContextHolder.get();
        get(id);
        boolean deleted = documentAssetRepository.markDeleted(
                id, documentId, context.userId(), LocalDateTime.now());
        if (!deleted) {
            throw new BusinessException(ApiError.DOCUMENT_NOT_FOUND);
        }
        knowledgeBaseRepository.refreshDocumentStats(id, context.userId(), LocalDateTime.now());
        try {
            kbSegmentRepository.deleteByAssetId(documentId);
        } catch (BusinessException e) {
            log.warn("document segment cleanup failed, assetId={}", documentId, e);
        }
    }

    private String requireId(String id, String fieldName) {
        if (!StringUtils.hasText(id)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, fieldName + " cannot be blank.");
        }
        return id.trim();
    }

    private String requireName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "name cannot be blank.");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "name length must be <= 128.");
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private PageBounds normalizePage(int page, int size) {
        int normalizedPage = page <= 0 ? DEFAULT_PAGE : page;
        int normalizedSize = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new PageBounds(normalizedPage, normalizedSize, (normalizedPage - 1) * normalizedSize);
    }

    private record PageBounds(int page, int size, int offset) {
    }
}
