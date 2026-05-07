package com.smart.vision.core.ingestion.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.ingestion.application.TextAssetIngestionService;
import com.smart.vision.core.ingestion.application.assembler.BatchTaskAssembler;
import com.smart.vision.core.ingestion.domain.model.BatchTask;
import com.smart.vision.core.ingestion.domain.model.BatchTaskItem;
import com.smart.vision.core.ingestion.domain.model.BatchTaskItemStatus;
import com.smart.vision.core.ingestion.domain.model.AssetType;
import com.smart.vision.core.ingestion.domain.model.TextAssetMetadata;
import com.smart.vision.core.ingestion.domain.model.TextChunk;
import com.smart.vision.core.ingestion.domain.model.TextAssetType;
import com.smart.vision.core.ingestion.domain.model.TextParseResult;
import com.smart.vision.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.smart.vision.core.ingestion.domain.repository.TextSegmentRepository;
import com.smart.vision.core.common.util.IdGen;
import com.smart.vision.core.ingestion.infrastructure.parser.TextChunkSplitter;
import com.smart.vision.core.ingestion.infrastructure.parser.TextParserRouter;
import com.smart.vision.core.ingestion.interfaces.rest.dto.BatchTaskStatusDTO;
import com.smart.vision.core.ingestion.interfaces.rest.dto.TextBatchProcessDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * text ingestion framework service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextAssetIngestionServiceImpl implements TextAssetIngestionService {

    private static final String TEXT_TASK_CACHE_PREFIX = "kb:text:task:";
    private static final String TEXT_TASK_LOCK_PREFIX = "kb:text:task:lock:";
    private static final String TEXT_ASSET_META_CACHE_PREFIX = "kb:text:asset:";
    private static final long TEXT_TASK_TTL_HOURS = 24L;
    private static final long TEXT_TASK_LOCK_TTL_SECONDS = 600L;

    @Qualifier("ingestionTaskExecutor")
    private final Executor ingestionTaskExecutor;
    private final TextParserRouter textParserRouter;
    private final TextChunkSplitter textChunkSplitter;
    private final IngestionEmbeddingPort embeddingPort;
    private final TextSegmentRepository textSegmentRepository;
    private final BatchTaskAssembler batchTaskAssembler;
    private final StringRedisTemplate redisTemplate;
    private final IdGen idGen;
    private final ObjectMapper objectMapper;

    @Override
    public BatchTaskStatusDTO submitBatchTask(List<TextBatchProcessDTO> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ApiError.TEXT_BATCH_ITEMS_REQUIRED);
        }

        long now = System.currentTimeMillis();
        List<BatchTaskItem> taskItems = new ArrayList<>();

        for (TextBatchProcessDTO item : items) {
            String fileName = normalizeFileName(item.getFileName());
            if (!TextAssetType.isSupported(fileName, item.getMimeType())) {
                throw new BusinessException(ApiError.TEXT_FILE_TYPE_NOT_SUPPORTED);
            }
        }

        for (TextBatchProcessDTO item : items) {
            String fileName = normalizeFileName(item.getFileName());

            String assetId = String.valueOf(idGen.nextId());

            TextAssetMetadata metadata = new TextAssetMetadata();
            metadata.setAssetId(assetId);
            metadata.setTitle(StringUtils.hasText(item.getTitle()) ? item.getTitle().trim() : fileName);
            metadata.setFileName(fileName);
            metadata.setMimeType(item.getMimeType());
            metadata.setObjectKey(item.getKey());
            metadata.setFileHash(item.getFileHash());
            metadata.setCreatedAt(now);
            metadata.setUpdatedAt(now);
            saveAssetMetadata(metadata);

            BatchTaskItem taskItem = new BatchTaskItem();
            taskItem.setItemId(assetId);
            taskItem.setAssetType(AssetType.TEXT);
            taskItem.setKey(item.getKey());
            taskItem.setFileName(fileName);
            taskItem.setFileHash(item.getFileHash());
            taskItem.setStatus(BatchTaskItemStatus.PENDING);
            taskItem.setErrorMessage(null);
            taskItem.setRetryCount(0);
            taskItem.setUpdatedAt(now);
            taskItems.add(taskItem);
        }

        BatchTask task = BatchTask.createPending(UUID.randomUUID().toString(), taskItems, now);
        saveTask(task);
        ingestionTaskExecutor.execute(() -> processTask(task.getTaskId()));
        return batchTaskAssembler.toTaskDto(task);
    }

    @Override
    public BatchTaskStatusDTO getBatchTaskStatus(String taskId) {
        BatchTask task = loadTask(taskId);
        if (task == null) {
            throw new BusinessException(ApiError.TEXT_TASK_NOT_FOUND);
        }
        return batchTaskAssembler.toTaskDto(task);
    }

    @Override
    public BatchTaskStatusDTO retryBatchTaskItem(String taskId, String itemId) {
        BatchTask task = loadTask(taskId);
        if (task == null) {
            throw new BusinessException(ApiError.TEXT_TASK_NOT_FOUND);
        }

        task.retryItem(itemId, System.currentTimeMillis());
        saveTask(task);
        ingestionTaskExecutor.execute(() -> processTask(task.getTaskId()));
        return batchTaskAssembler.toTaskDto(task);
    }

    @Override
    public BatchTaskStatusDTO retryAllFailedBatchTaskItems(String taskId) {
        BatchTask task = loadTask(taskId);
        if (task == null) {
            throw new BusinessException(ApiError.TEXT_TASK_NOT_FOUND);
        }

        task.retryAllFailed(System.currentTimeMillis());
        saveTask(task);
        ingestionTaskExecutor.execute(() -> processTask(task.getTaskId()));
        return batchTaskAssembler.toTaskDto(task);
    }

    private void processTask(String taskId) {
        String lockValue = UUID.randomUUID().toString();
        if (!acquireTaskLock(taskId, lockValue)) {
            return;
        }

        try {
            BatchTask task = loadTask(taskId);
            if (task == null) {
                return;
            }

            if (!task.hasPendingItems()) {
                task.refreshSummary(System.currentTimeMillis());
                saveTask(task);
                return;
            }

            java.util.List<BatchTaskItem> pendingItems = task.pendingItems();
            if (pendingItems.isEmpty()) {
                task.refreshSummary(System.currentTimeMillis());
                saveTask(task);
                return;
            }

            java.util.List<java.util.concurrent.CompletableFuture<Void>> futures = pendingItems.stream()
                    .map(item -> java.util.concurrent.CompletableFuture.runAsync(
                            () -> processTaskItem(task, item.getItemId()),
                            ingestionTaskExecutor
                    ))
                    .toList();

            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
            synchronized (task) {
                task.refreshSummary(System.currentTimeMillis());
                saveTask(task);
            }
        } finally {
            releaseTaskLock(taskId, lockValue);
        }
    }

    private void processTaskItem(BatchTask task, String itemId) {
        synchronized (task) {
            task.markItemRunning(itemId, System.currentTimeMillis());
            saveTask(task);
        }

        try {
            TextAssetMetadata metadata = loadAssetMetadata(itemId);
            if (metadata == null) {
                throw new BusinessException(ApiError.TEXT_ASSET_META_NOT_FOUND);
            }

            var parserOpt = textParserRouter.route(metadata);
            if (parserOpt.isEmpty()) {
                throw new BusinessException(ApiError.TEXT_PARSER_UNAVAILABLE);
            }

            TextParseResult parseResult = parserOpt.get().parse(metadata);
            if (parseResult == null) {
                throw new BusinessException(ApiError.TEXT_PARSE_FAILED);
            }

            List<TextChunk> chunks = textChunkSplitter.split(metadata, parseResult);
            enrichChunkEmbeddings(chunks);
            textSegmentRepository.save(metadata.getAssetId(), chunks);

            metadata.setUpdatedAt(System.currentTimeMillis());
            saveAssetMetadata(metadata);

            synchronized (task) {
                task.markItemSuccess(itemId, System.currentTimeMillis());
                saveTask(task);
            }
        } catch (Exception e) {
            log.warn("text task item failed [{}]: {}", itemId, e.getMessage());
            synchronized (task) {
                task.markItemFailed(itemId, e.getMessage(), System.currentTimeMillis());
                saveTask(task);
            }
        }
    }

    private boolean acquireTaskLock(String taskId, String lockValue) {
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                TEXT_TASK_LOCK_PREFIX + taskId,
                lockValue,
                TEXT_TASK_LOCK_TTL_SECONDS,
                TimeUnit.SECONDS
        );
        return Boolean.TRUE.equals(locked);
    }

    private void releaseTaskLock(String taskId, String lockValue) {
        String lockKey = TEXT_TASK_LOCK_PREFIX + taskId;
        String current = redisTemplate.opsForValue().get(lockKey);
        if (java.util.Objects.equals(current, lockValue)) {
            redisTemplate.delete(lockKey);
        }
    }

    private void saveTask(BatchTask task) {
        redisTemplate.opsForValue().set(
                TEXT_TASK_CACHE_PREFIX + task.getTaskId(),
                serializeTask(batchTaskAssembler.toTaskDto(task)),
                TEXT_TASK_TTL_HOURS,
                TimeUnit.HOURS
        );
    }

    private BatchTask loadTask(String taskId) {
        String raw = redisTemplate.opsForValue().get(TEXT_TASK_CACHE_PREFIX + taskId);
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            BatchTaskStatusDTO dto = objectMapper.readValue(raw, BatchTaskStatusDTO.class);
            return batchTaskAssembler.toTaskDomain(dto);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ApiError.INGEST_TASK_PAYLOAD_INVALID, e);
        }
    }

    private String serializeTask(BatchTaskStatusDTO task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ApiError.INGEST_TASK_PAYLOAD_SERIALIZE_FAILED, e);
        }
    }

    private void saveAssetMetadata(TextAssetMetadata metadata) {
        try {
            redisTemplate.opsForValue().set(
                    TEXT_ASSET_META_CACHE_PREFIX + metadata.getAssetId(),
                    objectMapper.writeValueAsString(metadata),
                    TEXT_TASK_TTL_HOURS,
                    TimeUnit.HOURS
            );
        } catch (JsonProcessingException e) {
            throw new BusinessException(ApiError.INGEST_TASK_PAYLOAD_SERIALIZE_FAILED, e);
        }
    }

    private TextAssetMetadata loadAssetMetadata(String assetId) {
        String raw = redisTemplate.opsForValue().get(TEXT_ASSET_META_CACHE_PREFIX + assetId);
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, TextAssetMetadata.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ApiError.INGEST_TASK_PAYLOAD_INVALID, e);
        }
    }

    private String normalizeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "text-asset-" + System.currentTimeMillis();
        }
        return fileName.trim();
    }

    private void enrichChunkEmbeddings(List<TextChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (TextChunk chunk : chunks) {
            if (chunk == null || !StringUtils.hasText(chunk.getChunkText())) {
                continue;
            }
            List<Float> embedding = embeddingPort.embedText(chunk.getChunkText());
            if (embedding == null || embedding.isEmpty()) {
                throw new BusinessException(ApiError.EMBEDDING_RESULT_EMPTY);
            }
            chunk.setEmbedding(embedding);
        }
    }
}
