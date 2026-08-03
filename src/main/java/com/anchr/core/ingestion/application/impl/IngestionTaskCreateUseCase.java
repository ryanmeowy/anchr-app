package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.application.IngestionApplicationService.IngestionCreateCommand;
import com.anchr.core.ingestion.application.IngestionApplicationService.IngestionCreateItemCommand;
import com.anchr.core.ingestion.application.IngestionApplicationService.IngestionTaskCreateResult;
import com.anchr.core.ingestion.application.IngestionCapabilityService;
import com.anchr.core.ingestion.application.IngestionTaskProcessor;
import com.anchr.core.ingestion.application.acl.IngestionActivityAcl;
import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjection;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjectionPolicy;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
final class IngestionTaskCreateUseCase {
    private static final int MAX_BATCH_ITEMS = 50;
    private static final String CLIENT_REQUEST_ID_PATTERN = "[A-Za-z0-9._:-]+";

    private final KnowledgeBaseService knowledgeBaseService;
    private final AssetRepository assetRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final IngestionTaskRepository ingestionTaskRepository;
    private final IngestionCapabilityService ingestionCapabilityService;
    private final IdGen idGen;
    private final IngestionActivityAcl ingestionActivityAcl;
    private final IngestionTaskProcessor ingestionTaskProcessor;
    private final IngestionCreateTransactionRunner transactionRunner;
    private final IngestionTaskQuery taskQuery;
    private final IngestionTaskFactory taskFactory = new IngestionTaskFactory();

    IngestionTaskCreateUseCase(KnowledgeBaseService knowledgeBaseService,
                               AssetRepository assetRepository,
                               KnowledgeBaseRepository knowledgeBaseRepository,
                               IngestionTaskRepository ingestionTaskRepository,
                               IngestionCapabilityService ingestionCapabilityService,
                               IdGen idGen,
                               IngestionActivityAcl ingestionActivityAcl,
                               IngestionTaskProcessor ingestionTaskProcessor,
                               IngestionCreateTransactionRunner transactionRunner,
                               IngestionTaskQuery taskQuery) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.assetRepository = assetRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.ingestionTaskRepository = ingestionTaskRepository;
        this.ingestionCapabilityService = ingestionCapabilityService;
        this.idGen = idGen;
        this.ingestionActivityAcl = ingestionActivityAcl;
        this.ingestionTaskProcessor = ingestionTaskProcessor;
        this.transactionRunner = transactionRunner;
        this.taskQuery = taskQuery;
    }

    IngestionTaskCreateResult create(String kbId, IngestionCreateCommand command) {
        String normalizedKbId = requireText(kbId, "kbId");
        RequestUserContext context = UserContextHolder.get();
        NormalizedCreateRequest normalized = normalizeCreateRequest(command);
        String requestHash = normalized.clientRequestId() == null ? null : IngestionRequestHasher.hash(
                normalizedKbId, normalized.sourceType(), normalized.dedupeStrategy(), normalized.items());

        // Acceptance is durable even if the KB is archived later. Resolve a prior request before
        // validating that the KB is still active so a replay can never be misreported as a safe-to-clean 404.
        if (normalized.clientRequestId() != null) {
            Optional<IngestionTask> existing = ingestionTaskRepository.findByClientRequestId(
                    context.userId(), normalized.clientRequestId());
            if (existing.isPresent()) {
                return replayOrReject(existing.get(), normalizedKbId, requestHash);
            }
        }

        validateUploadLimits(normalized);
        knowledgeBaseService.get(normalizedKbId);
        try {
            IngestionTask created = transactionRunner.write(() -> createNewTask(
                    context, normalizedKbId, normalized, requestHash));
            return new IngestionTaskCreateResult(created, true);
        } catch (DuplicateKeyException duplicate) {
            if (normalized.clientRequestId() == null || !isClientRequestUniqueConflict(duplicate)) {
                throw duplicate;
            }
            Optional<IngestionTask> winner = transactionRunner.read(() ->
                    ingestionTaskRepository.findByClientRequestId(
                            context.userId(), normalized.clientRequestId()));
            if (winner.isEmpty()) {
                throw duplicate;
            }
            return replayOrReject(winner.get(), normalizedKbId, requestHash);
        }
    }

    private IngestionTask createNewTask(RequestUserContext context,
                                        String kbId,
                                        NormalizedCreateRequest request,
                                        String requestHash) {
        LocalDateTime now = LocalDateTime.now();
        List<IngestionTaskItem> items = createItems(
                context, kbId, request.dedupeStrategy(), request.items(), now);
        IngestionTask task = taskFactory.build(
                context, kbId, request.sourceType(), items, now,
                request.clientRequestId(), requestHash);
        ingestionTaskRepository.save(task);
        ingestionActivityAcl.recordDocumentImported(task);
        knowledgeBaseRepository.refreshDocumentStats(kbId, context.userId(), false);
        submitAfterCommit(kbId, task.getId(), context.userId());
        return taskQuery.get(kbId, task.getId());
    }

    private List<IngestionTaskItem> createItems(RequestUserContext context,
                                                String kbId,
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
            items.add(createItem(context, kbId, taskId, dedupeStrategy, command, now));
        }
        return items;
    }

    private IngestionTaskItem createItem(RequestUserContext context,
                                         String kbId,
                                         String taskId,
                                         DedupeStrategy dedupeStrategy,
                                         IngestionCreateItemCommand command,
                                         LocalDateTime now) {
        String fileType = normalizeFileType(command.fileType());
        String fileName = normalizeFileName(command.fileName());
        IngestionPublicProjection pendingProjection =
                IngestionPublicProjectionPolicy.intakePending();
        if (!ingestionCapabilityService.isSupportedFileType(fileType)) {
            return failedItem(kbId, taskId, fileName, command.fileHash(),
                    dedupeStrategy, null, "UNSUPPORTED_FILE_TYPE", "Unsupported file type.", now);
        }
        DedupeDecision decision = resolveDedupeDecision(kbId, dedupeStrategy, command.fileHash());
        if (decision.result() == DedupeResult.SKIPPED) {
            return skippedItem(kbId, taskId, decision.existingAsset(), dedupeStrategy, now);
        }

        Asset document = createDocument(context, kbId, command, fileName, fileType, decision, now);
        assetRepository.save(document);
        String itemId = idGen.nextIdStr();
        return IngestionTaskItem.builder()
                .id(itemId)
                .taskId(taskId)
                .kbId(kbId)
                .assetId(document.getId())
                .targetIndexGeneration(1L)
                .fileName(fileName)
                .fileHash(trimToNull(command.fileHash()))
                .stage(pendingProjection.stage())
                .status(pendingProjection.status())
                .progress(pendingProjection.progress())
                .dedupeStrategy(dedupeStrategy)
                .dedupeResult(decision.result())
                .duplicateAssetId(decision.duplicateAssetId())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Asset createDocument(RequestUserContext context,
                                 String kbId,
                                 IngestionCreateItemCommand command,
                                 String fileName,
                                 String fileType,
                                 DedupeDecision decision,
                                 LocalDateTime now) {
        return Asset.builder()
                .id(idGen.nextIdStr())
                .kbId(kbId)
                .fileName(fileName)
                .title(trimToNull(command.title()))
                .fileType(fileType)
                .mimeType(trimToNull(command.mimeType()))
                .sizeBytes(command.sizeBytes())
                .fileHash(trimToNull(command.fileHash()))
                .versionGroupId(decision.versionGroupId())
                .versionNo(decision.versionNo())
                .objectKey(requireText(command.objectKey(), "objectKey"))
                .parseStatus(DocumentParseStatus.PENDING)
                .indexStatus(DocumentIndexStatus.PENDING)
                .segmentCount(0)
                .indexedSegmentCount(0)
                .createdBy(context.userId())
                .updatedBy(context.userId())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private IngestionTaskItem failedItem(String kbId,
                                         String taskId,
                                         String fileName,
                                         String fileHash,
                                         DedupeStrategy dedupeStrategy,
                                         String duplicateAssetId,
                                         String errorCode,
                                         String errorMessage,
                                         LocalDateTime now) {
        IngestionPublicProjection projection =
                IngestionPublicProjectionPolicy.preflightFailure();
        return IngestionTaskItem.builder()
                .id(idGen.nextIdStr())
                .taskId(taskId)
                .kbId(kbId)
                .fileName(fileName)
                .fileHash(trimToNull(fileHash))
                .stage(projection.stage())
                .status(projection.status())
                .progress(projection.progress())
                .dedupeStrategy(dedupeStrategy)
                .dedupeResult(null)
                .duplicateAssetId(duplicateAssetId)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .createdAt(now)
                .updatedAt(now)
                .finishedAt(now)
                .build();
    }

    private IngestionTaskItem skippedItem(String kbId,
                                          String taskId,
                                          Asset existing,
                                          DedupeStrategy dedupeStrategy,
                                          LocalDateTime now) {
        IngestionPublicProjection projection =
                IngestionPublicProjectionPolicy.skipped();
        return IngestionTaskItem.builder()
                .id(idGen.nextIdStr())
                .taskId(taskId)
                .kbId(kbId)
                .assetId(existing.getId())
                .fileName(existing.getFileName())
                .fileHash(existing.getFileHash())
                .stage(projection.stage())
                .status(projection.status())
                .progress(projection.progress())
                .dedupeStrategy(dedupeStrategy)
                .dedupeResult(DedupeResult.SKIPPED)
                .duplicateAssetId(existing.getId())
                .createdAt(now)
                .updatedAt(now)
                .finishedAt(now)
                .build();
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

    private DedupeDecision resolveDedupeDecision(String kbId,
                                                 DedupeStrategy strategy,
                                                 String fileHash) {
        if (!StringUtils.hasText(fileHash)) {
            return DedupeDecision.newAsset(null, 1);
        }
        var existing = assetRepository.findActiveByHash(kbId, fileHash.trim());
        if (existing.isEmpty()) {
            return DedupeDecision.newAsset(null, 1);
        }
        Asset existingAsset = existing.get();
        if (strategy == DedupeStrategy.SKIP) {
            return DedupeDecision.skipped(existingAsset);
        }
        if (strategy == DedupeStrategy.OVERWRITE) {
            return DedupeDecision.overwritten(existingAsset);
        }
        String versionGroupId = StringUtils.hasText(existingAsset.getVersionGroupId())
                ? existingAsset.getVersionGroupId()
                : existingAsset.getId();
        int nextVersionNo = Math.max(
                existingAsset.getVersionNo() == null ? 1 : existingAsset.getVersionNo(),
                assetRepository.findMaxVersionNo(kbId, versionGroupId)) + 1;
        return DedupeDecision.versioned(existingAsset, versionGroupId, nextVersionNo);
    }

    private NormalizedCreateRequest normalizeCreateRequest(IngestionCreateCommand command) {
        if (command == null) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "request cannot be null.");
        }
        if (CollectionUtils.isEmpty(command.items())) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "items cannot be empty.");
        }
        if (command.items().size() > MAX_BATCH_ITEMS) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "items size must be <= 50.");
        }
        IngestionSourceType sourceType = command.sourceType() == null
                ? IngestionSourceType.UPLOAD : command.sourceType();
        if (sourceType != IngestionSourceType.UPLOAD) {
            throw new BusinessException(
                    ApiError.INVALID_REQUEST, "sourceType must be UPLOAD for ingestion creation.");
        }
        DedupeStrategy dedupeStrategy = command.dedupeStrategy() == null
                ? DedupeStrategy.SKIP : command.dedupeStrategy();
        List<IngestionCreateItemCommand> items = new ArrayList<>(command.items().size());
        for (IngestionCreateItemCommand item : command.items()) {
            if (item == null) {
                throw new BusinessException(ApiError.INVALID_REQUEST, "items cannot contain null.");
            }
            items.add(new IngestionCreateItemCommand(
                    normalizeFileName(item.fileName()),
                    trimToNull(item.title()),
                    normalizeFileType(item.fileType()),
                    trimToNull(item.mimeType()),
                    item.sizeBytes(),
                    requireText(item.objectKey(), "objectKey"),
                    trimToNull(item.fileHash())));
        }
        return new NormalizedCreateRequest(
                normalizeOptionalClientRequestId(command.clientRequestId()),
                sourceType,
                dedupeStrategy,
                List.copyOf(items));
    }

    private void validateUploadLimits(NormalizedCreateRequest request) {
        for (IngestionCreateItemCommand item : request.items()) {
            if (ingestionCapabilityService.isSupportedFileType(item.fileType())) {
                validateFileSize(item.fileName(), item.fileType(), item.sizeBytes());
            }
        }
    }

    private void validateFileSize(String fileName, String fileType, Long sizeBytes) {
        if (sizeBytes == null) {
            return;
        }
        if (sizeBytes < 0) {
            throw new BusinessException(ApiError.INVALID_REQUEST,
                    "sizeBytes must be greater than or equal to 0.");
        }
        long maxSizeBytes = ingestionCapabilityService.maxFileSizeBytesFor(fileType);
        if (sizeBytes > maxSizeBytes) {
            throw new BusinessException(ApiError.UPLOAD_TOO_LARGE,
                    fileName + " exceeds the allowed size for " + fileType + ".");
        }
    }

    private IngestionTaskCreateResult replayOrReject(IngestionTask existing,
                                                     String kbId,
                                                     String requestHash) {
        if (!kbId.equals(existing.getKbId())
                || requestHash == null
                || !requestHash.equals(existing.getRequestHash())) {
            throw new BusinessException(ApiError.IDEMPOTENCY_KEY_REUSED);
        }
        return new IngestionTaskCreateResult(existing, false);
    }

    private boolean isClientRequestUniqueConflict(DuplicateKeyException exception) {
        Throwable cause = exception;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && message.contains("uk_ingestion_task_creator_request")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String normalizeOptionalClientRequestId(String clientRequestId) {
        if (clientRequestId == null) {
            return null;
        }
        return requireClientRequestId(clientRequestId);
    }

    private String requireClientRequestId(String clientRequestId) {
        String normalized = requireText(clientRequestId, "clientRequestId");
        if (normalized.length() > 128) {
            throw new BusinessException(
                    ApiError.INVALID_REQUEST, "clientRequestId length must be <= 128.");
        }
        if (!normalized.matches(CLIENT_REQUEST_ID_PATTERN)) {
            throw new BusinessException(
                    ApiError.INVALID_REQUEST, "clientRequestId contains unsupported characters.");
        }
        return normalized;
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

    private String normalizeFileName(String fileName) {
        return requireText(fileName, "fileName");
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record NormalizedCreateRequest(String clientRequestId,
                                           IngestionSourceType sourceType,
                                           DedupeStrategy dedupeStrategy,
                                           List<IngestionCreateItemCommand> items) {
    }

    private record DedupeDecision(DedupeResult result,
                                  Asset existingAsset,
                                  String duplicateAssetId,
                                  String versionGroupId,
                                  Integer versionNo) {

        private static DedupeDecision newAsset(String versionGroupId, Integer versionNo) {
            return new DedupeDecision(
                    DedupeResult.NEW, null, null, versionGroupId, versionNo);
        }

        private static DedupeDecision skipped(Asset existingAsset) {
            return new DedupeDecision(
                    DedupeResult.SKIPPED, existingAsset, existingAsset.getId(), null, null);
        }

        private static DedupeDecision overwritten(Asset existingAsset) {
            return new DedupeDecision(
                    DedupeResult.OVERWRITTEN,
                    existingAsset,
                    existingAsset.getId(),
                    existingAsset.getVersionGroupId(),
                    existingAsset.getVersionNo() == null ? 1 : existingAsset.getVersionNo());
        }

        private static DedupeDecision versioned(Asset existingAsset,
                                                String versionGroupId,
                                                Integer versionNo) {
            return new DedupeDecision(
                    DedupeResult.VERSIONED,
                    existingAsset,
                    existingAsset.getId(),
                    versionGroupId,
                    versionNo);
        }
    }
}
