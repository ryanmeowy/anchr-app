package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.settings.domain.model.IngestionRuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.application.IngestionTaskProcessor;
import com.anchr.core.ingestion.application.acl.IngestionDoclingAcl;
import com.anchr.core.ingestion.application.acl.IngestionRetrievalAcl;
import com.anchr.core.ingestion.application.acl.IngestionStorageAcl;
import com.anchr.core.ingestion.application.model.IngestionIndexSegment;
import com.anchr.core.ingestion.application.model.IngestionRuntimeSettings;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.parser.DoclingChunkMapper;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Single-instance ingestion worker.
 *
 * <p>One claimed item runs from whole-document parse through index activation in the same
 * worker. Provider retry state and Docling job identity stay in memory. A process restart does
 * not pretend to resume the old call: startup marks residual RUNNING items failed and the user
 * may explicitly retry the whole document.</p>
 */
@Slf4j
@Service
public class IngestionTaskProcessorImpl implements IngestionTaskProcessor {
    private static final long TASK_POLL_INTERVAL_MILLIS = 1_000L;
    private static final long TASK_POLL_INITIAL_DELAY_MILLIS = 1_000L;
    private final Set<String> locallyDispatchedItems = ConcurrentHashMap.newKeySet();

    private final Executor ingestionTaskExecutor;
    private final IngestionTaskRepository ingestionTaskRepository;
    private final AssetRepository assetRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final IngestionIndexFinalizer ingestionIndexFinalizer;
    private final IngestionRetrievalAcl ingestionRetrievalAcl;
    private final IngestionStageTransactionCoordinator transactionCoordinator;
    private final IngestionParseStage parseStage;
    private final IngestionEmbeddingStage embeddingStage;
    private final IngestionWorkerFailureClassifier failureClassifier;
    private final RuntimeConfigUnit runtimeConfigUnit;

    public IngestionTaskProcessorImpl(
            @Qualifier("ingestionTaskExecutor") Executor ingestionTaskExecutor,
            IngestionTaskRepository ingestionTaskRepository,
            AssetRepository assetRepository,
            KnowledgeBaseRepository knowledgeBaseRepository,
            IngestionEmbeddingPort embeddingPort,
            AesUtil aesUtil,
            IngestionIndexFinalizer ingestionIndexFinalizer,
            IngestionRetrievalAcl ingestionRetrievalAcl,
            IngestionStageTransactionCoordinator transactionCoordinator,
            IngestionObjectStoragePort objectStoragePort,
            IngestionStorageAcl ingestionStorageAcl,
            DoclingChunkMapper doclingChunkMapper,
            IngestionDoclingAcl ingestionDoclingAcl,
            ObjectMapper objectMapper,
            IdGen idGen,
            RuntimeConfigUnit runtimeConfigUnit) {
        this.ingestionTaskExecutor = ingestionTaskExecutor;
        this.ingestionTaskRepository = ingestionTaskRepository;
        this.assetRepository = assetRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.ingestionIndexFinalizer = ingestionIndexFinalizer;
        this.ingestionRetrievalAcl = ingestionRetrievalAcl;
        this.transactionCoordinator = transactionCoordinator;
        this.runtimeConfigUnit = runtimeConfigUnit;
        this.parseStage = new IngestionParseStage(
                objectStoragePort,
                ingestionStorageAcl,
                doclingChunkMapper,
                ingestionDoclingAcl,
                aesUtil,
                objectMapper);
        this.embeddingStage = new IngestionEmbeddingStage(
                embeddingPort, objectStoragePort, idGen);
        this.failureClassifier = new IngestionWorkerFailureClassifier();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedItemsAfterRestart() {
        for (IngestionTaskItem item : ingestionTaskRepository.listRunningItems()) {
            Asset asset = tryFindAsset(item);
            String message = "服务重启，原处理过程不可继续，请重新执行该文档。";
            try {
                transactionCoordinator.failRunning(
                        item, asset, ApiError.INTERNAL_ERROR, message,
                        DocumentParseStatus.FAILED.name(), DocumentIndexStatus.FAILED.name());
            } catch (RuntimeException exception) {
                log.error("failed to settle interrupted ingestion item, itemId={}: {}",
                        item.getId(), exception.getMessage(), exception);
            }
        }
    }

    @Scheduled(
            fixedDelay = TASK_POLL_INTERVAL_MILLIS,
            initialDelay = TASK_POLL_INITIAL_DELAY_MILLIS
    )
    public void pollPendingItems() {
        try {
            dispatch(ingestionTaskRepository.listPendingItemIds(
                    runtimeConfigUnit.getInt(
                            RuntimeConfigType.INGESTION,
                            IngestionRuntimeConfigKey.CLAIM_BATCH_SIZE,
                            32)));
        } catch (RuntimeException exception) {
            log.warn("failed to scan pending ingestion items: {}",
                    exception.getMessage(), exception);
        }
    }

    @Override
    public void submit(String kbId, String taskId, String userId) {
        if (!StringUtils.hasText(taskId)) return;
        try {
            dispatch(ingestionTaskRepository.listPendingItemIds(
                    taskId,
                    runtimeConfigUnit.getInt(
                            RuntimeConfigType.INGESTION,
                            IngestionRuntimeConfigKey.CLAIM_BATCH_SIZE,
                            32)));
        } catch (RuntimeException exception) {
            log.debug("ingestion wake-up deferred to scheduler, taskId={}, reason={}",
                    taskId, exception.getMessage());
        }
    }

    private void dispatch(List<String> itemIds) {
        if (itemIds == null) return;
        for (String itemId : itemIds) {
            if (!StringUtils.hasText(itemId) || !locallyDispatchedItems.add(itemId)) continue;
            try {
                ingestionTaskExecutor.execute(() -> processCandidate(itemId));
            } catch (RejectedExecutionException rejected) {
                locallyDispatchedItems.remove(itemId);
                log.debug("ingestion executor saturated; item remains pending, itemId={}", itemId);
            } catch (RuntimeException exception) {
                locallyDispatchedItems.remove(itemId);
                log.warn("failed to dispatch ingestion item, itemId={}: {}",
                        itemId, exception.getMessage());
            }
        }
    }

    private void processCandidate(String itemId) {
        try {
            ingestionTaskRepository.claimPending(itemId).ifPresent(this::processItem);
        } catch (RuntimeException exception) {
            log.warn("failed to claim ingestion item, itemId={}: {}",
                    itemId, exception.getMessage(), exception);
        } finally {
            locallyDispatchedItems.remove(itemId);
        }
    }

    void processItem(IngestionTaskItem claimedItem) {
        IngestionTaskItem item = claimedItem;
        Asset asset = null;
        String doclingJobId = null;
        IngestionRuntimeSettings runtimeConfig =
                IngestionRuntimeSettings.load(runtimeConfigUnit);
        try {
            item = transactionCoordinator.ensureTargetIndexGeneration(item);
            asset = findAsset(item);
            if (!transactionCoordinator.updateAssetStatus(
                    item, asset,
                    DocumentParseStatus.RUNNING.name(),
                    DocumentIndexStatus.PENDING.name())) {
                return;
            }

            IngestionParseStage.ParseRunContext parseContext =
                    parseStage.createContext(
                            item,
                            asset,
                            runtimeConfig.embeddedImageUploadEnabled(),
                            runtimeConfig.chunkMinTokens(),
                            runtimeConfig.chunkMaxTokens(),
                            runtimeConfig.doclingMaxResponseBytes());
            IngestionParseStage.ParsedJob parsedJob = parseStage.parse(
                    parseContext,
                    asset,
                    runtimeConfig.parseStageTimeout(),
                    runtimeConfig.parsePollInterval(),
                    runtimeConfig.stageMaxRetries());
            doclingJobId = parsedJob.jobId();

            if (!transactionCoordinator.advanceAndUpdateAssetStatus(
                    item, IngestionStage.EMBED, 55, asset,
                    DocumentParseStatus.SUCCESS.name(),
                    DocumentIndexStatus.PENDING.name())) {
                return;
            }
            item = item.toBuilder()
                    .stage(IngestionStage.EMBED)
                    .progress(Math.max(item.getProgress(), 55))
                    .build();

            IngestionParseStage.ParsedChunks parsedChunks =
                    parseStage.mapChunks(item, asset, parsedJob.result());
            IngestionEmbeddingStage.PreparedSegments prepared = embeddingStage.prepare(
                    asset,
                    parsedChunks,
                    new IngestionEmbeddingStage.Settings(
                            runtimeConfig.embeddingMinIntervalMs(),
                            runtimeConfig.embeddingRateLimitMaxAttempts(),
                            runtimeConfig.embeddingRateLimitBackoffMs()));
            if (!transactionCoordinator.advanceAndUpdateAssetStatus(
                    item, IngestionStage.INDEX, 75, asset,
                    DocumentParseStatus.SUCCESS.name(),
                    DocumentIndexStatus.RUNNING.name())) {
                return;
            }
            item = item.toBuilder()
                    .stage(IngestionStage.INDEX)
                    .progress(Math.max(item.getProgress(), 75))
                    .build();

            List<IngestionIndexSegment> segments = prepared.segments();
            var writeReceipt = ingestionRetrievalAcl.replaceGeneration(
                    item,
                    asset,
                    segments,
                    prepared.embeddingProfileFingerprint());
            if (!ingestionIndexFinalizer.activateGeneration(
                    item,
                    asset,
                    IngestionIndexFinalizer.countReadableSegments(segments),
                    writeReceipt)) {
                return;
            }
            parseStage.acknowledgeBestEffort(doclingJobId);
            refreshKnowledgeBaseStats(item);
        } catch (RuntimeException exception) {
            IngestionWorkerFailureClassifier.Failure failure =
                    failureClassifier.classify(exception);
            failItem(item, asset, failure.error(), failure.message(), doclingJobId);
        }
    }

    private void failItem(IngestionTaskItem item,
                          Asset asset,
                          ApiError error,
                          String message,
                          String jobId) {
        if (item == null) return;
        boolean failed = transactionCoordinator.failRunning(
                item, asset, error, message,
                DocumentParseStatus.FAILED.name(), DocumentIndexStatus.FAILED.name());
        if (!failed) return;
        parseStage.acknowledgeBestEffort(jobId);
        log.warn("knowledge-base ingestion item failed, taskId={}, itemId={}, stage={}, errorCode={}, error={}",
                item.getTaskId(), item.getId(), item.getStage(), error, message);
    }

    private Asset findAsset(IngestionTaskItem item) {
        if (item == null || !StringUtils.hasText(item.getAssetId())) {
            throw new BusinessException(
                    ApiError.DOCUMENT_NOT_FOUND,
                    "Task item is not linked to a document asset.");
        }
        return assetRepository.findActiveById(item.getKbId(), item.getAssetId())
                .orElseThrow(() -> new BusinessException(ApiError.DOCUMENT_NOT_FOUND));
    }

    private Asset tryFindAsset(IngestionTaskItem item) {
        if (item == null || !StringUtils.hasText(item.getKbId())
                || !StringUtils.hasText(item.getAssetId())) {
            return null;
        }
        try {
            return assetRepository.findActiveById(
                    item.getKbId(), item.getAssetId()).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void refreshKnowledgeBaseStats(IngestionTaskItem item) {
        try {
            knowledgeBaseRepository.refreshDocumentStats(
                    item.getKbId(), updatedBy(item), true);
        } catch (RuntimeException exception) {
            log.warn("failed to refresh knowledge-base stats after ingestion, kbId={}, itemId={}: {}",
                    item.getKbId(), item.getId(), exception.getMessage());
        }
    }

    private String updatedBy(IngestionTaskItem item) {
        return StringUtils.hasText(item.getTaskCreatedBy())
                ? item.getTaskCreatedBy() : "ingestion-worker";
    }
}
