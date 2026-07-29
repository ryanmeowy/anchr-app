package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.application.IngestionTaskProcessor;
import com.anchr.core.ingestion.application.acl.IngestionActivityAcl;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjection;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjectionPolicy;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.domain.repository.AssetRepository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

final class IngestionTaskMaintenanceUseCase {
    private final KnowledgeBaseService knowledgeBaseService;
    private final AssetRepository assetRepository;
    private final IngestionTaskRepository ingestionTaskRepository;
    private final IdGen idGen;
    private final IngestionActivityAcl ingestionActivityAcl;
    private final IngestionTaskProcessor ingestionTaskProcessor;
    private final IngestionTaskQuery taskQuery;
    private final IngestionTaskFactory taskFactory;

    IngestionTaskMaintenanceUseCase(KnowledgeBaseService knowledgeBaseService,
                                    AssetRepository assetRepository,
                                    IngestionTaskRepository ingestionTaskRepository,
                                    IdGen idGen,
                                    IngestionActivityAcl ingestionActivityAcl,
                                    IngestionTaskProcessor ingestionTaskProcessor,
                                    IngestionTaskQuery taskQuery,
                                    IngestionTaskFactory taskFactory) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.assetRepository = assetRepository;
        this.ingestionTaskRepository = ingestionTaskRepository;
        this.idGen = idGen;
        this.ingestionActivityAcl = ingestionActivityAcl;
        this.ingestionTaskProcessor = ingestionTaskProcessor;
        this.taskQuery = taskQuery;
        this.taskFactory = taskFactory;
    }

    IngestionTask retryItem(String kbId, String taskId, String itemId) {
        IngestionTask task = taskQuery.get(kbId, taskId);
        RequestUserContext context = UserContextHolder.get();
        String normalizedItemId = requireText(itemId, "itemId");
        IngestionTaskItem visibleItem = task.getItems().stream()
                .filter(candidate -> normalizedItemId.equals(candidate.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ApiError.INGEST_TASK_ITEM_NOT_FOUND));
        if (visibleItem.getStatus() != IngestionTaskItemStatus.FAILED) {
            throw new BusinessException(ApiError.INGEST_RETRY_ONLY_FAILED);
        }
        IngestionTaskItem item = ingestionTaskRepository.findRetryItem(
                        kbId, task.getId(), normalizedItemId)
                .orElseThrow(() -> new BusinessException(
                        ApiError.INGEST_RETRY_ONLY_FAILED,
                        "Failed item has no retryable execution."));
        LocalDateTime now = LocalDateTime.now();
        resetFailedItemForRetry(kbId, task.getId(), item, now);
        ingestionTaskRepository.refreshSummary(
                kbId, task.getId(), context.userId(), now);
        submitAfterCommit(kbId, task.getId(), context.userId());
        return taskQuery.get(kbId, task.getId());
    }

    IngestionTask retryFailed(String kbId, String taskId) {
        IngestionTask task = taskQuery.get(kbId, taskId);
        RequestUserContext context = UserContextHolder.get();
        List<IngestionTaskItem> failedItems =
                ingestionTaskRepository.listFailedItems(kbId, task.getId());
        if (failedItems.isEmpty()) {
            throw new BusinessException(ApiError.INGEST_NO_FAILED_ITEMS);
        }
        LocalDateTime now = LocalDateTime.now();
        for (IngestionTaskItem item : failedItems) {
            resetFailedItemForRetry(kbId, task.getId(), item, now);
        }
        ingestionTaskRepository.refreshSummary(
                kbId, task.getId(), context.userId(), now);
        submitAfterCommit(kbId, task.getId(), context.userId());
        return taskQuery.get(kbId, task.getId());
    }

    IngestionTask createReparseTask(String kbId, String assetId) {
        return createDocumentMaintenanceTask(kbId, assetId, IngestionSourceType.REPARSE);
    }

    IngestionTask createReembedTask(String kbId, String assetId) {
        return createDocumentMaintenanceTask(kbId, assetId, IngestionSourceType.REEMBED);
    }

    private void resetFailedItemForRetry(String kbId,
                                         String taskId,
                                         IngestionTaskItem item,
                                         LocalDateTime updatedAt) {
        Asset asset = assetRepository.findByIdForUpdate(kbId, item.getAssetId())
                .filter(candidate -> candidate.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ApiError.DOCUMENT_NOT_FOUND));
        long nextGeneration = Math.addExact(
                Math.max(asset.getActiveIndexGeneration(),
                        ingestionTaskRepository.findMaxTargetIndexGeneration(asset.getId())),
                1L);
        boolean reset = ingestionTaskRepository.resetFailedItem(
                kbId, taskId, item.getId(), nextGeneration, updatedAt);
        if (!reset) {
            throw new BusinessException(
                    ApiError.INGEST_RETRY_ONLY_FAILED,
                    "Failed item changed while retry was being prepared.");
        }
    }

    private IngestionTask createDocumentMaintenanceTask(String kbId,
                                                        String assetId,
                                                        IngestionSourceType sourceType) {
        knowledgeBaseService.getDocument(kbId, assetId);
        Asset document = assetRepository.findByIdForUpdate(kbId, assetId)
                .filter(asset -> asset.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ApiError.DOCUMENT_NOT_FOUND));
        long targetIndexGeneration = Math.addExact(
                Math.max(document.getActiveIndexGeneration(),
                        ingestionTaskRepository.findMaxTargetIndexGeneration(document.getId())),
                1L);
        RequestUserContext context = UserContextHolder.get();
        LocalDateTime now = LocalDateTime.now();
        String taskId = idGen.nextIdStr();
        String itemId = idGen.nextIdStr();
        IngestionPublicProjection projection =
                IngestionPublicProjectionPolicy.pending(sourceType);
        IngestionTaskItem item = IngestionTaskItem.builder()
                .id(itemId)
                .taskId(taskId)
                .kbId(kbId)
                .assetId(document.getId())
                .targetIndexGeneration(targetIndexGeneration)
                .fileName(document.getFileName())
                .fileHash(document.getFileHash())
                .stage(projection.stage())
                .status(projection.status())
                .progress(projection.progress())
                .dedupeStrategy(null)
                .dedupeResult(null)
                .duplicateAssetId(null)
                .createdAt(now)
                .updatedAt(now)
                .build();
        IngestionTask task = taskFactory.build(
                context, kbId, sourceType, List.of(item), now);
        ingestionTaskRepository.save(task);
        ingestionActivityAcl.recordDocumentImported(task);
        if (sourceType == IngestionSourceType.REPARSE) {
            assetRepository.updateStatuses(
                    kbId,
                    document.getId(),
                    DocumentParseStatus.PENDING.name(),
                    DocumentIndexStatus.PENDING.name(),
                    context.userId(),
                    now);
        } else {
            assetRepository.updateStatuses(
                    kbId,
                    document.getId(),
                    document.getParseStatus().name(),
                    DocumentIndexStatus.PENDING.name(),
                    context.userId(),
                    now);
        }
        submitAfterCommit(kbId, task.getId(), context.userId());
        return taskQuery.get(kbId, task.getId());
    }

    private void submitAfterCommit(String kbId, String taskId, String userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            ingestionTaskProcessor.submit(kbId, taskId, userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ingestionTaskProcessor.submit(kbId, taskId, userId);
            }
        });
    }

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, fieldName + " cannot be blank.");
        }
        return value.trim();
    }
}
