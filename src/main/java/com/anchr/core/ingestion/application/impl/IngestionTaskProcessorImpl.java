package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.ingestion.application.IngestionTaskProcessor;
import com.anchr.core.ingestion.domain.model.*;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.parser.DoclingChunkMapper;
import com.anchr.core.ingestion.infrastructure.persistence.es.SegmentBulkWriter;
import com.anchr.core.integration.ai.client.DoclingClient;
import com.anchr.core.integration.ai.ParseRequest;
import com.anchr.core.integration.ai.ParseResponse;
import com.anchr.core.integration.storage.StorageTokenIssuer;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.search.domain.model.AssetType;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.settings.domain.model.StorageConfig;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Async executor for DB-backed knowledge base ingestion tasks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionTaskProcessorImpl implements IngestionTaskProcessor {

    private static final String TASK_LOCK_PREFIX = "kb:ingestion:task:lock:";
    private static final int STAGE_PARSE_PROGRESS = 20;
    private static final int STAGE_EMBED_PROGRESS = 55;
    private static final int STAGE_INDEX_PROGRESS = 75;
    private static final int STAGE_DONE_PROGRESS = 100;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    private final Map<String, ReentrantLock> taskLocks = new ConcurrentHashMap<>();
    private final Object embeddingPaceLock = new Object();
    private long nextEmbeddingCallAt;

    @Qualifier("ingestionTaskExecutor")
    private final Executor ingestionTaskExecutor;
    private final IngestionTaskRepository ingestionTaskRepository;
    private final AssetRepository assetRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final IngestionEmbeddingPort embeddingPort;
    private final AesUtil aesUtil;
    private final SegmentBulkWriter segmentBulkWriter;
    private final IngestionObjectStoragePort objectStoragePort;
    private final StorageConfigRepository storageConfigRepository;
    private final DoclingChunkMapper doclingChunkMapper;
    private final Gson gson;

    @Value("${app.embedding.ingestion-min-interval-ms:1500}")
    private long embeddingMinIntervalMs;

    @Value("${app.embedding.ingestion-rate-limit-max-attempts:5}")
    private int embeddingRateLimitMaxAttempts;

    @Value("${app.embedding.ingestion-rate-limit-backoff-ms:5000}")
    private long embeddingRateLimitBackoffMs;

    @Value("${app.docling.base-url:http://127.0.0.1:8091}")
    private String doclingBaseUrl;

    @Override
    public void submit(String kbId, String taskId, String userId) {
        ingestionTaskExecutor.execute(() -> processTask(kbId, taskId, userId));
    }

    private void processTask( String kbId, String taskId, String userId) {
        // Instance-level lock; use a distributed lock for multi-instance deployments.
        ReentrantLock lock = taskLocks.computeIfAbsent(TASK_LOCK_PREFIX + taskId, key -> new ReentrantLock());
        if (!lock.tryLock()) {
            return;
        }
        try {
            IngestionTask task = ingestionTaskRepository.findById(kbId, taskId).orElse(null);
            if (task == null || task.getItems() == null || task.getItems().isEmpty()) {
                return;
            }
            for (IngestionTaskItem item : task.getItems()) {
                if (item.getStatus() == IngestionTaskItemStatus.PENDING) {
                    processItem(kbId, taskId, item, userId);
                }
            }
        } finally {
            refreshTask(kbId, taskId, userId);
            lock.unlock();
            taskLocks.remove(TASK_LOCK_PREFIX + taskId);
        }
    }

    private void processItem( String kbId, String taskId, IngestionTaskItem item, String userId) {
        Asset asset = findAsset(kbId, item);
        try {
            processAsset(kbId, taskId, item, asset, userId);
            knowledgeBaseRepository.refreshDocumentStats(kbId, userId, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("knowledge base ingestion item failed, taskId={}, itemId={}, assetId={}: {}",
                    taskId, item.getId(), item.getAssetId(), e.getMessage());
            failItem(kbId, taskId, item, asset, userId, e);
        }
    }

    private void processAsset(String kbId, String taskId, IngestionTaskItem item,
                              Asset asset, String userId) {
        updateRunning(kbId, taskId, item.getId(), IngestionStage.PARSE, STAGE_PARSE_PROGRESS, userId);
        assetRepository.updateStatuses(kbId, asset.getId(),
                DocumentParseStatus.RUNNING.name(), DocumentIndexStatus.PENDING.name(), userId, LocalDateTime.now());

        DoclingClient docling = new DoclingClient(doclingBaseUrl);
        ParseResponse parsed = docling.parse(buildParseRequest(asset, taskId, item.getId()));
        if (parsed.chunks() == null || parsed.chunks().isEmpty()) {
            throw new BusinessException(ApiError.TEXT_PARSE_FAILED, "docling returned empty chunks.");
        }

        List<Chunk> chunks = doclingChunkMapper.toTextChunks(asset, parsed.chunks());

        updateRunning(kbId, taskId, item.getId(), IngestionStage.EMBED, STAGE_EMBED_PROGRESS, userId);
        // TODO Image vector capability is pending support.
        if (!isImage(asset)) {
            enrichTextEmbeddings(chunks);
        }
        updateRunning(kbId, taskId, item.getId(), IngestionStage.INDEX, STAGE_INDEX_PROGRESS, userId);
        assetRepository.updateStatuses(kbId, asset.getId(),
                DocumentParseStatus.SUCCESS.name(), DocumentIndexStatus.RUNNING.name(), userId, LocalDateTime.now());
        List<Segment> segments = buildSegments(asset, chunks);
        segmentBulkWriter.write(segments);
        completeItem(kbId, taskId, item.getId(), asset.getId(), chunks.size(), userId);
    }

    private ParseRequest buildParseRequest(Asset asset, String taskId, String itemId) {
        return ParseRequest.builder()
                .requestId(buildRequestId(taskId, itemId))
                .fileName(asset.getFileName())
                .sourceUrl(objectStoragePort.buildDownloadUrl(asset.getObjectKey()))
                .options(ParseRequest.Options.chunkModel())
                .oss(buildOssConfig())
                .build();
    }

    private String buildRequestId(String taskId, String itemId) {
        taskId = StringUtils.hasText(taskId) ? taskId : UUID.randomUUID().toString();
        itemId = StringUtils.hasText(itemId) ? itemId : UUID.randomUUID().toString();
        return String.format("%s:%s", taskId, itemId);
    }

    private ParseRequest.Oss buildOssConfig() {
        Optional<StorageConfig> configOpt = storageConfigRepository.find();
        if (configOpt.isEmpty()) {
            return null;
        }
        StorageConfig config = configOpt.get();
        try {
            StorageTokenIssuer issuer = new StorageTokenIssuer();
            String accessKey = aesUtil.decrypt(config.getAccessKeyEnc());
            String secretKey = aesUtil.decrypt(config.getSecretKeyEnc());
            Map<String, Object> stsResult = issuer.issueToken(config, accessKey, secretKey);
            String json = gson.toJson(stsResult);
            String encrypt = aesUtil.encrypt(json);
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            return new ParseRequest.Oss(
                    config.getEndpoint(),
                    config.getBucket(),
                    config.getPrefix(),
                    Map.of("iv", Base64.getEncoder().encodeToString(iv),
                            "ciphertext", encrypt));
        } catch (Exception e) {
            log.warn("Failed to build OSS credentials for docling: {}", e.getMessage());
            return null;
        }
    }

    private Asset findAsset(String kbId, IngestionTaskItem item) {
        if (!StringUtils.hasText(item.getAssetId())) {
            throw new BusinessException(ApiError.DOCUMENT_NOT_FOUND, "Task item is not linked to a document asset.");
        }
        return assetRepository.findActiveById(kbId, item.getAssetId())
                .orElseThrow(() -> new BusinessException(ApiError.DOCUMENT_NOT_FOUND));
    }

    private List<Segment> buildSegments(Asset asset, List<Chunk> chunks) {
        return chunks.stream()
                .filter(Objects::nonNull)
                .filter(chunk -> StringUtils.hasText(chunk.getSegmentId()))
                .map(chunk -> Segment.builder()
                        .segmentId(chunk.getSegmentId())
                        .kbId(chunk.getKbId())
                        .assetId(asset.getId())
                        .assetType(isImage(asset) ? AssetType.IMAGE : AssetType.TEXT)
                        .segmentType(isImage(asset) ? SegmentType.IMAGE_OCR_BLOCK : SegmentType.TEXT_CHUNK)
                        .title(chunk.getTitle())
                        .contentText(chunk.getChunkText())
                        .embedding(chunk.getEmbedding())
                        .pageNo(chunk.getPageNo())
                        .chunkOrder(chunk.getChunkOrder())
                        .sourceRef(chunk.getSourceRef())
                        .createdAt(System.currentTimeMillis())
                        .bbox(chunk.getBboxInfos())
                        .build())
                .toList();

    }

    private void updateRunning(String kbId, String taskId, String itemId,
                               IngestionStage stage, int progress, String userId) {
        LocalDateTime now = LocalDateTime.now();
        ingestionTaskRepository.markItemRunning(kbId, taskId, itemId, stage.name(), progress, now);
        ingestionTaskRepository.refreshSummary(kbId, taskId, userId, now);
    }

    private void completeItem(String kbId, String taskId, String itemId,
                              String assetId, int segmentCount, String userId) {
        LocalDateTime now = LocalDateTime.now();
        assetRepository.updateIngestionResult(kbId, assetId,
                DocumentParseStatus.SUCCESS.name(), DocumentIndexStatus.SUCCESS.name(), segmentCount,
                null, null, userId, now);
        ingestionTaskRepository.markItemSuccess(kbId, taskId, itemId,
                IngestionStage.ASKABLE.name(), STAGE_DONE_PROGRESS, now);
        ingestionTaskRepository.refreshSummary(kbId, taskId, userId, now);
    }

    private void failItem(String kbId, String taskId, IngestionTaskItem item,
                          Asset asset, String userId, Exception e) {
        LocalDateTime now = LocalDateTime.now();
        String errorCode = e instanceof BusinessException businessException
                ? businessException.getError().name()
                : ApiError.INTERNAL_ERROR.name();
        String errorMessage = clip(e.getMessage(), ERROR_MESSAGE_MAX_LENGTH);
        assetRepository.updateIngestionResult(kbId, asset.getId(),
                DocumentParseStatus.FAILED.name(), DocumentIndexStatus.FAILED.name(), asset.getSegmentCount(),
                errorCode, errorMessage, userId, now);
        ingestionTaskRepository.markItemFailed(kbId, taskId, item.getId(),
                item.getStage().name(), item.getProgress(), errorCode, errorMessage, now);
        ingestionTaskRepository.refreshSummary(kbId, taskId, userId, now);
    }

    private void refreshTask(String kbId, String taskId, String userId) {
        ingestionTaskRepository.refreshSummary(kbId, taskId, userId, LocalDateTime.now());
    }

    private boolean isImage(Asset asset) {
        return "IMAGE".equalsIgnoreCase(asset.getFileType());
    }

    private void enrichTextEmbeddings(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (Chunk chunk : chunks) {
            if (chunk == null || !StringUtils.hasText(chunk.getChunkText())) {
                continue;
            }
            List<Float> embedding = embedTextWithRetry(chunk.getChunkText());
            if (embedding == null || embedding.isEmpty()) {
                throw new BusinessException(ApiError.EMBEDDING_RESULT_EMPTY);
            }
            chunk.setEmbedding(embedding);
        }
    }

    private List<Float> embedTextWithRetry(String text) {
        return callEmbeddingWithRetry(() -> embeddingPort.embed(text, "text"), "text");
    }

    private List<Float> embedImageWithRetry(String imageInput) {
        return callEmbeddingWithRetry(() -> embeddingPort.embed(imageInput, "image"), "image");
    }

    private List<Float> callEmbeddingWithRetry(Supplier<List<Float>> call, String inputType) {
        int maxAttempts = Math.max(1, embeddingRateLimitMaxAttempts);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            reserveEmbeddingCallSlot();
            try {
                return call.get();
            } catch (RuntimeException e) {
                lastError = e;
                if (!isRateLimitError(e) || attempt >= maxAttempts) {
                    throw e;
                }
                long waitMs = resolveEmbeddingBackoffMs(attempt);
                log.warn("embedding rate limited, inputType={}, attempt={}/{}, waitMs={}",
                        inputType, attempt, maxAttempts, waitMs);
                sleep(waitMs);
            }
        }
        throw lastError == null ? new BusinessException(ApiError.EMBEDDING_FAILED) : lastError;
    }

    private void reserveEmbeddingCallSlot() {
        long waitMs;
        synchronized (embeddingPaceLock) {
            long now = System.currentTimeMillis();
            waitMs = Math.max(0L, nextEmbeddingCallAt - now);
            nextEmbeddingCallAt = Math.max(now, nextEmbeddingCallAt) + Math.max(0L, embeddingMinIntervalMs);
        }
        if (waitMs > 0L) {
            sleep(waitMs);
        }
    }

    private long resolveEmbeddingBackoffMs(int attempt) {
        long base = Math.max(1000L, embeddingRateLimitBackoffMs);
        long multiplier = 1L << Math.min(attempt - 1, 4);
        return base * multiplier;
    }

    private boolean isRateLimitError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (containsRateLimitMarker(message)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsRateLimitMarker(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return message.contains("429")
                || message.contains("Throttling")
                || message.contains("RateQuota")
                || message.toLowerCase().contains("rate limit");
    }

    private void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Ingestion task was interrupted.", e);
        }
    }

    private String clip(String text, int maxLen) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen);
    }
}
