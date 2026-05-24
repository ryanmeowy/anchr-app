package com.anchr.core.kb.application.impl;

import com.anchr.core.auth.application.AuditLogService;
import com.anchr.core.auth.application.PermissionService;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.infrastructure.id.PrefixedIdGenerator;
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

/**
 * Default knowledge base application service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private static final String KB_ID_PREFIX = "kb";
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentAssetRepository documentAssetRepository;
    private final PrefixedIdGenerator idGenerator;
    private final KbSegmentRepository kbSegmentRepository;
    private final PermissionService permissionService;
    private final AuditLogService auditLogService;

    @Override
    @Transactional
    public KnowledgeBase create(String name, String description) {
        permissionService.requireImport();
        String normalizedName = requireName(name);
        RequestUserContext context = UserContextHolder.get();
        LocalDateTime now = LocalDateTime.now();
        KnowledgeBase knowledgeBase = KnowledgeBase.builder()
                .id(idGenerator.nextId(KB_ID_PREFIX))
                .workspaceId(context.workspaceId())
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
        auditLogService.record("KB_CREATED", "KB", knowledgeBase.getId(), "SUCCESS", "{}");
        return knowledgeBase;
    }

    @Override
    public PagedResult<KnowledgeBase> list(int page, int size) {
        PageBounds bounds = normalizePage(page, size);
        RequestUserContext context = UserContextHolder.get();
        return new PagedResult<>(
                knowledgeBaseRepository.listActive(context.workspaceId(), bounds.size(), bounds.offset()),
                knowledgeBaseRepository.countActive(context.workspaceId()),
                bounds.page(),
                bounds.size());
    }

    @Override
    public KnowledgeBase get(String kbId) {
        RequestUserContext context = UserContextHolder.get();
        return knowledgeBaseRepository.findActiveById(context.workspaceId(), requireId(kbId, "kbId"))
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
                context.workspaceId(), id, normalizedName, trimToNull(description), context.userId(), now);
        if (!updated) {
            throw new BusinessException(ApiError.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return get(id);
    }

    @Override
    @Transactional
    public void archive(String kbId) {
        permissionService.requireDelete();
        RequestUserContext context = UserContextHolder.get();
        boolean archived = knowledgeBaseRepository.archive(
                context.workspaceId(), requireId(kbId, "kbId"), context.userId(), LocalDateTime.now());
        if (!archived) {
            throw new BusinessException(ApiError.KNOWLEDGE_BASE_NOT_FOUND);
        }
    }

    @Override
    public KnowledgeBaseStats getStats(String kbId) {
        RequestUserContext context = UserContextHolder.get();
        return knowledgeBaseRepository.findStats(context.workspaceId(), requireId(kbId, "kbId"))
                .orElseThrow(() -> new BusinessException(ApiError.KNOWLEDGE_BASE_NOT_FOUND));
    }

    @Override
    public PagedResult<DocumentAsset> listDocuments(String kbId, int page, int size) {
        String id = requireId(kbId, "kbId");
        get(id);
        PageBounds bounds = normalizePage(page, size);
        RequestUserContext context = UserContextHolder.get();
        return new PagedResult<>(
                documentAssetRepository.listActive(context.workspaceId(), id, bounds.size(), bounds.offset()),
                documentAssetRepository.countActive(context.workspaceId(), id),
                bounds.page(),
                bounds.size());
    }

    @Override
    public DocumentAsset getDocument(String kbId, String assetId) {
        String id = requireId(kbId, "kbId");
        RequestUserContext context = UserContextHolder.get();
        get(id);
        return documentAssetRepository.findActiveById(context.workspaceId(), id, requireId(assetId, "assetId"))
                .orElseThrow(() -> new BusinessException(ApiError.DOCUMENT_NOT_FOUND));
    }

    @Override
    @Transactional
    public void deleteDocument(String kbId, String assetId) {
        permissionService.requireDelete();
        String id = requireId(kbId, "kbId");
        String documentId = requireId(assetId, "assetId");
        RequestUserContext context = UserContextHolder.get();
        get(id);
        boolean deleted = documentAssetRepository.markDeleted(
                context.workspaceId(), id, documentId, context.userId(), LocalDateTime.now());
        if (!deleted) {
            throw new BusinessException(ApiError.DOCUMENT_NOT_FOUND);
        }
        auditLogService.record("DOCUMENT_DELETED", "DOCUMENT", documentId, "SUCCESS",
                "{\"kbId\":\"" + id + "\"}");
        knowledgeBaseRepository.refreshDocumentStats(context.workspaceId(), id, context.userId(), LocalDateTime.now());
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
