package com.anchr.core.ingestion.application.impl;

import com.anchr.core.ingestion.application.IngestionTaskProcessor;
import com.anchr.core.kb.application.ActivityEventService;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.ingestion.application.IngestionCapabilityService;
import com.anchr.core.ingestion.application.IngestionApplicationService;
import com.anchr.core.kb.domain.model.DocumentAsset;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import com.anchr.core.kb.domain.repository.DocumentAssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Default unified knowledge base ingestion application service.
 */
@Service
@RequiredArgsConstructor
public class IngestionApplicationServiceImpl implements IngestionApplicationService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_BATCH_ITEMS = 50;

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentAssetRepository documentAssetRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final IngestionTaskRepository ingestionTaskRepository;
    private final IngestionCapabilityService ingestionCapabilityService;
    private final IdGen idGen;
    private final ActivityEventService activityEventService;
    private final IngestionTaskProcessor ingestionTaskProcessor;

    @Override
    @Transactional
    public IngestionTask createTask(String kbId, IngestionCreateCommand command) {

        knowledgeBaseService.get(kbId);
        RequestUserContext context = UserContextHolder.get();
        LocalDateTime now = LocalDateTime.now();
        IngestionSourceType sourceType = command.sourceType() == null ? IngestionSourceType.UPLOAD : command.sourceType();
        DedupeStrategy dedupeStrategy = command.dedupeStrategy() == null ? DedupeStrategy.SKIP : command.dedupeStrategy();
        List<IngestionTaskItem> items = createItems(context, kbId, sourceType, dedupeStrategy, command.items(), now);
        IngestionTask task = buildTask(context, kbId, sourceType, items, now);
        ingestionTaskRepository.save(task);
        activityEventService.recordDocumentImported(task.getId(), task.getKbId(), task.getStatus().name(),
                task.getTotalCount(), task.getSuccessCount(), task.getFailureCount(), task.getRunningCount());
        knowledgeBaseRepository.refreshDocumentStats(kbId, context.userId(), now);
        submitAfterCommit(kbId, task.getId(), context.userId());
        return getTask(kbId, task.getId());
    }

    @Override
    public List<IngestionTask> listTasks(String kbId, IngestionTaskStatus status, int limit) {
        knowledgeBaseService.get(kbId);
        return ingestionTaskRepository.list(kbId, status, normalizeLimit(limit));
    }

    @Override
    public IngestionTask getTask(String kbId, String taskId) {
        knowledgeBaseService.get(kbId);
        return ingestionTaskRepository.findById(kbId, requireText(taskId, "taskId"))
                .orElseThrow(() -> new BusinessException(ApiError.INGESTION_TASK_NOT_FOUND));
    }

    @Override
    @Transactional
    public IngestionTask retryItem(String kbId, String taskId, String itemId) {

        IngestionTask task = getTask(kbId, taskId);
        RequestUserContext context = UserContextHolder.get();
        var item = ingestionTaskRepository.findItem(kbId, task.getId(), requireText(itemId, "itemId"))
                .orElseThrow(() -> new BusinessException(ApiError.INGEST_TASK_ITEM_NOT_FOUND));
        if (item.getStatus() != IngestionTaskItemStatus.FAILED) {
            throw new BusinessException(ApiError.INGEST_RETRY_ONLY_FAILED);
        }
        LocalDateTime now = LocalDateTime.now();
        ingestionTaskRepository.resetFailedItem(kbId, task.getId(), item.getId(), now);
        ingestionTaskRepository.refreshSummary(kbId, task.getId(), context.userId(), now);
        submitAfterCommit(kbId, task.getId(), context.userId());
        return getTask(kbId, task.getId());
    }

    @Override
    @Transactional
    public IngestionTask retryFailed(String kbId, String taskId) {

        IngestionTask task = getTask(kbId, taskId);
        RequestUserContext context = UserContextHolder.get();
        List<IngestionTaskItem> failedItems =
                ingestionTaskRepository.listFailedItems(kbId, task.getId());
        if (failedItems.isEmpty()) {
            throw new BusinessException(ApiError.INGEST_NO_FAILED_ITEMS);
        }
        LocalDateTime now = LocalDateTime.now();
        ingestionTaskRepository.resetFailedItems(kbId, task.getId(), now);
        ingestionTaskRepository.refreshSummary(kbId, task.getId(), context.userId(), now);
        submitAfterCommit(kbId, task.getId(), context.userId());
        return getTask(kbId, task.getId());
    }

    @Override
    @Transactional
    public IngestionTask createReparseTask(String kbId, String assetId) {

        return createDocumentMaintenanceTask(kbId, assetId, IngestionSourceType.REPARSE, IngestionStage.PARSE);
    }

    @Override
    @Transactional
    public IngestionTask createReembedTask(String kbId, String assetId) {

        return createDocumentMaintenanceTask(kbId, assetId, IngestionSourceType.REEMBED, IngestionStage.EMBED);
    }

    private IngestionTask createDocumentMaintenanceTask(String kbId, String assetId,
                                                        IngestionSourceType sourceType, IngestionStage stage) {
        DocumentAsset document = knowledgeBaseService.getDocument(kbId, assetId);
        RequestUserContext context = UserContextHolder.get();
        LocalDateTime now = LocalDateTime.now();
        String taskId = idGen.nextIdStr();
        IngestionTaskItem item = IngestionTaskItem.builder()
                .id(idGen.nextIdStr())
                .taskId(taskId)
                .kbId(kbId)
                .assetId(document.getId())
                .fileName(document.getFileName())
                .fileHash(document.getFileHash())
                .sourceUrl(document.getSourceUrl())
                .stage(stage)
                .status(IngestionTaskItemStatus.PENDING)
                .progress(stage == IngestionStage.EMBED ? 60 : 20)
                .dedupeResult(null)
                .createdAt(now)
                .updatedAt(now)
                .build();
        IngestionTask task = buildTask(context, kbId, sourceType, List.of(item), now);
        ingestionTaskRepository.save(task);
        activityEventService.recordDocumentImported(task.getId(), task.getKbId(), task.getStatus().name(),
                task.getTotalCount(), task.getSuccessCount(), task.getFailureCount(), task.getRunningCount());
        if (sourceType == IngestionSourceType.REPARSE) {
            documentAssetRepository.updateStatuses(kbId, document.getId(),
                    DocumentParseStatus.PENDING.name(), DocumentIndexStatus.PENDING.name(), context.userId(), now);
        } else {
            documentAssetRepository.updateStatuses(kbId, document.getId(),
                    document.getParseStatus().name(), DocumentIndexStatus.PENDING.name(), context.userId(), now);
        }
        submitAfterCommit(kbId, task.getId(), context.userId());
        return getTask(kbId, task.getId());
    }

    private List<IngestionTaskItem> createItems(RequestUserContext context, String kbId, IngestionSourceType sourceType,
                                                DedupeStrategy dedupeStrategy,
                                                List<IngestionCreateItemCommand> commands,
                                                LocalDateTime now) {
        if (CollectionUtils.isEmpty(commands)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "items cannot be empty.");
        }
        if (commands.size() > MAX_BATCH_ITEMS) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "items size must be <= 50.");
        }
        String taskId = idGen.nextIdStr();
        List<IngestionTaskItem> items = new ArrayList<>();
        for (IngestionCreateItemCommand command : commands) {
            items.add(createItem(context, kbId, taskId, sourceType, dedupeStrategy, command, now));
        }
        return items;
    }

    private IngestionTaskItem createItem(RequestUserContext context, String kbId, String taskId,
                                         IngestionSourceType sourceType, DedupeStrategy dedupeStrategy,
                                         IngestionCreateItemCommand command, LocalDateTime now) {
        String fileType = normalizeFileType(command.fileType());
        String fileName = normalizeFileName(command.fileName(), command.sourceUrl());
        if (sourceType == IngestionSourceType.URL) {
            requireText(command.sourceUrl(), "sourceUrl");
            if (!ingestionCapabilityService.isSupportedFileType(fileType)) {
                return failedItem(kbId, taskId, fileName, command.fileHash(), command.sourceUrl(),
                        "UNSUPPORTED_FILE_TYPE", "Unsupported URL file type.", now);
            }
            DocumentAsset document = createDocument(context, kbId, command, fileName, fileType, now);
            documentAssetRepository.save(document);
            return IngestionTaskItem.builder()
                    .id(idGen.nextIdStr())
                    .taskId(taskId)
                    .kbId(kbId)
                    .assetId(document.getId())
                    .fileName(fileName)
                    .fileHash(trimToNull(command.fileHash()))
                    .sourceUrl(trimToNull(command.sourceUrl()))
                    .stage(IngestionStage.PARSE)
                    .status(IngestionTaskItemStatus.PENDING)
                    .progress(10)
                    .dedupeResult(resolveNewDedupeResult(dedupeStrategy))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
        }
        if (!ingestionCapabilityService.isSupportedFileType(fileType)) {
            return failedItem(kbId, taskId, fileName, command.fileHash(), command.sourceUrl(),
                    "UNSUPPORTED_FILE_TYPE", "Unsupported file type.", now);
        }
        if (StringUtils.hasText(command.fileHash()) && dedupeStrategy == DedupeStrategy.SKIP) {
            var existing = documentAssetRepository.findActiveByHash(kbId, command.fileHash().trim());
            if (existing.isPresent()) {
                return skippedItem(kbId, taskId, existing.get(), now);
            }
        }

        DocumentAsset document = createDocument(context, kbId, command, fileName, fileType, now);
        documentAssetRepository.save(document);
        return IngestionTaskItem.builder()
                .id(idGen.nextIdStr())
                .taskId(taskId)
                .kbId(kbId)
                .assetId(document.getId())
                .fileName(fileName)
                .fileHash(trimToNull(command.fileHash()))
                .sourceUrl(trimToNull(command.sourceUrl()))
                .stage(IngestionStage.UPLOAD)
                .status(IngestionTaskItemStatus.PENDING)
                .progress(0)
                .dedupeResult(resolveNewDedupeResult(dedupeStrategy))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private DocumentAsset createDocument(RequestUserContext context, String kbId, IngestionCreateItemCommand command,
                                         String fileName, String fileType, LocalDateTime now) {
        return DocumentAsset.builder()
                .id(idGen.nextIdStr())
                .kbId(kbId)
                .fileName(fileName)
                .title(trimToNull(command.title()))
                .fileType(fileType)
                .mimeType(trimToNull(command.mimeType()))
                .sizeBytes(command.sizeBytes())
                .fileHash(trimToNull(command.fileHash()))
                .objectKey(trimToNull(command.objectKey()))
                .sourceUrl(trimToNull(command.sourceUrl()))
                .parseStatus(DocumentParseStatus.PENDING)
                .indexStatus(DocumentIndexStatus.PENDING)
                .segmentCount(0)
                .createdBy(context.userId())
                .updatedBy(context.userId())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private IngestionTask buildTask(RequestUserContext context, String kbId, IngestionSourceType sourceType,
                                    List<IngestionTaskItem> items, LocalDateTime now) {
        int successCount = (int) items.stream()
                .filter(item -> item.getStatus() == IngestionTaskItemStatus.SUCCESS
                        || item.getStatus() == IngestionTaskItemStatus.SKIPPED)
                .count();
        int failureCount = (int) items.stream().filter(item -> item.getStatus() == IngestionTaskItemStatus.FAILED).count();
        int runningCount = (int) items.stream().filter(item -> item.getStatus() == IngestionTaskItemStatus.RUNNING).count();
        return IngestionTask.builder()
                .id(items.get(0).getTaskId())
                .kbId(kbId)
                .sourceType(sourceType)
                .status(resolveTaskStatus(items, successCount, failureCount, runningCount))
                .totalCount(items.size())
                .successCount(successCount)
                .failureCount(failureCount)
                .runningCount(runningCount)
                .createdBy(context.userId())
                .updatedBy(context.userId())
                .createdAt(now)
                .updatedAt(now)
                .finishedAt(hasPendingOrRunning(items) ? null : now)
                .items(items)
                .build();
    }

    private IngestionTaskItem failedItem(String kbId, String taskId, String fileName, String fileHash,
                                         String sourceUrl, String errorCode, String errorMessage,
                                         LocalDateTime now) {
        return IngestionTaskItem.builder()
                .id(idGen.nextIdStr())
                .taskId(taskId)
                .kbId(kbId)
                .fileName(fileName)
                .fileHash(trimToNull(fileHash))
                .sourceUrl(trimToNull(sourceUrl))
                .stage(IngestionStage.UPLOAD)
                .status(IngestionTaskItemStatus.FAILED)
                .progress(0)
                .dedupeResult(null)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .createdAt(now)
                .updatedAt(now)
                .finishedAt(now)
                .build();
    }

    private IngestionTaskItem skippedItem(String kbId, String taskId, DocumentAsset existing, LocalDateTime now) {
        return IngestionTaskItem.builder()
                .id(idGen.nextIdStr())
                .taskId(taskId)
                .kbId(kbId)
                .assetId(existing.getId())
                .fileName(existing.getFileName())
                .fileHash(existing.getFileHash())
                .sourceUrl(existing.getSourceUrl())
                .stage(IngestionStage.ASKABLE)
                .status(IngestionTaskItemStatus.SKIPPED)
                .progress(100)
                .dedupeResult(DedupeResult.SKIPPED)
                .createdAt(now)
                .updatedAt(now)
                .finishedAt(now)
                .build();
    }

    private IngestionTaskStatus resolveTaskStatus(List<IngestionTaskItem> items, int successCount,
                                                  int failureCount, int runningCount) {
        if (runningCount > 0) {
            return IngestionTaskStatus.RUNNING;
        }
        if (hasPending(items)) {
            return IngestionTaskStatus.PENDING;
        }
        if (failureCount == 0) {
            return IngestionTaskStatus.SUCCESS;
        }
        if (successCount == 0) {
            return IngestionTaskStatus.FAILED;
        }
        return IngestionTaskStatus.PARTIAL_SUCCESS;
    }

    private boolean hasPending(List<IngestionTaskItem> items) {
        return items.stream().anyMatch(item -> item.getStatus() == IngestionTaskItemStatus.PENDING);
    }

    private boolean hasPendingOrRunning(List<IngestionTaskItem> items) {
        return items.stream().anyMatch(item -> item.getStatus() == IngestionTaskItemStatus.PENDING
                || item.getStatus() == IngestionTaskItemStatus.RUNNING);
    }

    private void submitAfterCommit( String kbId, String taskId, String userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            ingestionTaskProcessor.submit( kbId, taskId, userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ingestionTaskProcessor.submit( kbId, taskId, userId);
            }
        });
    }

    private DedupeResult resolveNewDedupeResult(DedupeStrategy strategy) {
        if (strategy == DedupeStrategy.OVERWRITE) {
            return DedupeResult.OVERWRITTEN;
        }
        if (strategy == DedupeStrategy.VERSIONED) {
            return DedupeResult.VERSIONED;
        }
        return DedupeResult.NEW;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, fieldName + " cannot be blank.");
        }
        return value.trim();
    }

    private String normalizeFileType(String fileType) {
        return requireText(fileType, "fileType").toUpperCase(Locale.ROOT);
    }

    private String normalizeFileName(String fileName, String sourceUrl) {
        if (StringUtils.hasText(fileName)) {
            return fileName.trim();
        }
        if (StringUtils.hasText(sourceUrl)) {
            return sourceUrl.trim();
        }
        throw new BusinessException(ApiError.INVALID_REQUEST, "fileName cannot be blank.");
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
