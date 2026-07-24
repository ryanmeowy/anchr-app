package com.anchr.core.kb.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.application.support.AssetIndexChangeRecorder;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.AssetHealthStats;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseHealth;
import com.anchr.core.kb.domain.model.KnowledgeBaseHealthScore;
import com.anchr.core.kb.domain.model.KnowledgeBaseStats;
import com.anchr.core.kb.domain.model.KnowledgeBaseStatus;
import com.anchr.core.kb.domain.model.SourceTypeCount;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.ActivityEventRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Default knowledge base application service.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_KB_SIZE = 20;
    private static final int DEFAULT_DOCUMENT_SIZE = 50;
    private static final int MAX_SIZE = 100;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final AssetRepository assetRepository;
    private final ActivityEventRepository activityEventRepository;
    private final AssetIndexChangeRecorder assetIndexChangeRecorder;
    private final IdGen idGen;

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

    @Override
    public PagedResult<KnowledgeBase> listKbs(String q, String status,
                                              LocalDateTime updatedAfter, LocalDateTime updatedBefore,
                                              Integer page, Integer size) {
        PageBounds bounds = normalizePage(page, size, DEFAULT_KB_SIZE);
        String trimmedQ = StringUtils.hasText(q) ? q.trim() : null;
        return new PagedResult<>(
                knowledgeBaseRepository.searchKbs(trimmedQ, status,
                        updatedAfter, updatedBefore, bounds.size(), bounds.offset()),
                knowledgeBaseRepository.countKbs(trimmedQ, status,
                        updatedAfter, updatedBefore),
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
    public List<KnowledgeBaseStats> getStats(List<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        return knowledgeBaseRepository.findStats(kbIds);
    }

    @Override
    public KnowledgeBaseHealth getHealth(String kbId) {
        String id = requireId(kbId, "kbId");
        KnowledgeBase kb = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ApiError.KNOWLEDGE_BASE_NOT_FOUND));

        AssetHealthStats stats = assetRepository.healthStats(id);
        List<SourceTypeCount> sourceTypeCounts = assetRepository.countByFileType(id);
        int documentTotal = stats.documentTotal();

        List<KnowledgeBaseHealth.SourceTypeHealth> sourceTypes = sourceTypeCounts.stream()
                .map(st -> KnowledgeBaseHealth.SourceTypeHealth.builder()
                        .type(st.type())
                        .label(st.type())
                        .count(st.count())
                        .percentage(documentTotal > 0
                                ? (int) Math.round(st.count() * 100.0 / documentTotal)
                                : 0)
                        .build())
                .toList();

        int score = KnowledgeBaseHealthScore.compute(
                kb.getStatus(),
                documentTotal, stats.documentIndexed(), stats.documentFailed(),
                stats.segmentTotal(), stats.segmentIndexed(),
                kb.getLastIngestedAt(), LocalDateTime.now());

        return KnowledgeBaseHealth.builder()
                .kbId(kb.getId())
                .kbName(kb.getName())
                .status(kb.getStatus().name())
                .score(score)
                .documents(KnowledgeBaseHealth.DocumentHealth.builder()
                        .total(documentTotal)
                        .indexed(stats.documentIndexed())
                        .pending(stats.documentPending())
                        .failed(stats.documentFailed())
                        .build())
                .segments(KnowledgeBaseHealth.SegmentHealth.builder()
                        .total(stats.segmentTotal())
                        .indexed(stats.segmentIndexed())
                        .build())
                .sourceTypes(sourceTypes)
                .build();
    }

    @Override
    public DocumentPagedResult listDocuments(String kbId, String keyword, String fileType,
                                             Integer page, Integer size) {
        String id = requireId(kbId, "kbId");
        get(id);
        PageBounds bounds = normalizePage(page, size, DEFAULT_DOCUMENT_SIZE);
        String normalizedKeyword = trimToNull(keyword);
        String normalizedFileType = StringUtils.hasText(fileType)
                ? fileType.trim().toUpperCase(Locale.ROOT)
                : null;
        return new DocumentPagedResult(
                assetRepository.listActive(id, normalizedKeyword, normalizedFileType,
                        bounds.size(), bounds.offset()),
                assetRepository.countActive(id, normalizedKeyword, normalizedFileType),
                assetRepository.sumActiveSegments(id, normalizedKeyword, normalizedFileType),
                bounds.page(),
                bounds.size());
    }

    @Override
    public Asset getDocument(String kbId, String assetId) {
        String id = requireId(kbId, "kbId");
        get(id);
        return assetRepository.findActiveById(id, requireId(assetId, "assetId"))
                .orElseThrow(() -> new BusinessException(ApiError.DOCUMENT_NOT_FOUND));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(String kbId, String assetId) {
        kbId = requireId(kbId, "kbId");
        assetId = requireId(assetId, "assetId");
        RequestUserContext context = UserContextHolder.get();
        get(kbId);
        LocalDateTime now = LocalDateTime.now();
        Asset asset = assetRepository.findByIdForUpdate(kbId, assetId)
                .filter(candidate -> candidate.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ApiError.DOCUMENT_NOT_FOUND));
        boolean deleted = assetRepository.markDeleted(
                kbId, assetId, context.userId(), now);
        if (!deleted) {
            throw new BusinessException(ApiError.DOCUMENT_NOT_FOUND);
        }
        activityEventRepository.deleteCitationOpenedByAssetId(context.userId(), assetId);
        knowledgeBaseRepository.refreshDocumentStats(kbId, context.userId(), false);
        assetIndexChangeRecorder.assetDeleted(
                kbId,
                assetId,
                asset.getActiveIndexGeneration(),
                context.userId(),
                now);
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

    private PageBounds normalizePage(Integer page, Integer size, int defaultSize) {
        int normalizedPage = null == page ? DEFAULT_PAGE : Math.max(DEFAULT_PAGE, page);
        int requestedSize = null == size ? defaultSize : size;
        int normalizedSize = Math.clamp(requestedSize, 1, MAX_SIZE);
        return new PageBounds(normalizedPage, normalizedSize, (normalizedPage - 1) * normalizedSize);
    }

    private record PageBounds(int page, int size, int offset) {
    }
}
