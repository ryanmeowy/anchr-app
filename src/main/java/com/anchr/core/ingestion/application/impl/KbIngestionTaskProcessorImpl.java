package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.model.GraphTriple;
import com.anchr.core.ingestion.application.KbIngestionTaskProcessor;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.model.OcrBoundingBox;
import com.anchr.core.ingestion.domain.model.OcrParagraph;
import com.anchr.core.ingestion.domain.model.OcrStructuredResult;
import com.anchr.core.ingestion.domain.model.TextAssetMetadata;
import com.anchr.core.ingestion.domain.model.TextChunk;
import com.anchr.core.ingestion.domain.model.TextParseResult;
import com.anchr.core.ingestion.domain.port.IngestionContentPort;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.ingestion.domain.port.IngestionOcrPort;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.domain.repository.TextSegmentRepository;
import com.anchr.core.ingestion.infrastructure.parser.TextAssetContentLoader;
import com.anchr.core.ingestion.infrastructure.parser.TextChunkSplitter;
import com.anchr.core.ingestion.infrastructure.parser.TextParserRouter;
import com.anchr.core.ingestion.infrastructure.persistence.es.KbSegmentBulkWriter;
import com.anchr.core.kb.domain.model.DocumentAsset;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.domain.repository.DocumentAssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.search.domain.model.Bbox;
import com.anchr.core.search.domain.model.KbAssetTypeEnum;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Async executor for DB-backed knowledge base ingestion tasks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbIngestionTaskProcessorImpl implements KbIngestionTaskProcessor {

    private static final String TASK_LOCK_PREFIX = "kb:ingestion:task:lock:";
    private static final int STAGE_PARSE_PROGRESS = 20;
    private static final int STAGE_CHUNK_PROGRESS = 40;
    private static final int STAGE_EMBED_PROGRESS = 65;
    private static final int STAGE_INDEX_PROGRESS = 85;
    private static final int STAGE_DONE_PROGRESS = 100;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    private final Object embeddingPaceLock = new Object();
    private long nextEmbeddingCallAt;

    @Qualifier("ingestionTaskExecutor")
    private final Executor ingestionTaskExecutor;
    private final IngestionTaskRepository ingestionTaskRepository;
    private final DocumentAssetRepository documentAssetRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final TextAssetContentLoader textAssetContentLoader;
    private final TextParserRouter textParserRouter;
    private final TextChunkSplitter textChunkSplitter;
    private final IngestionEmbeddingPort embeddingPort;
    private final TextSegmentRepository textSegmentRepository;
    private final IngestionObjectStoragePort objectStoragePort;
    private final IngestionOcrPort ocrPort;
    private final IngestionContentPort contentPort;
    private final KbSegmentBulkWriter kbSegmentBulkWriter;
    private final RedissonClient redissonClient;

    @Value("${app.embedding.ingestion-min-interval-ms:1500}")
    private long embeddingMinIntervalMs;

    @Value("${app.embedding.ingestion-rate-limit-max-attempts:5}")
    private int embeddingRateLimitMaxAttempts;

    @Value("${app.embedding.ingestion-rate-limit-backoff-ms:5000}")
    private long embeddingRateLimitBackoffMs;

    @Override
    public void submit(String workspaceId, String kbId, String taskId, String userId) {
        ingestionTaskExecutor.execute(() -> processTask(workspaceId, kbId, taskId, userId));
    }

    private void processTask(String workspaceId, String kbId, String taskId, String userId) {
        RLock lock = redissonClient.getLock(TASK_LOCK_PREFIX + taskId);
        if (!lock.tryLock()) {
            return;
        }
        try {
            IngestionTask task = ingestionTaskRepository.findById(workspaceId, kbId, taskId).orElse(null);
            if (task == null || task.getItems() == null || task.getItems().isEmpty()) {
                return;
            }
            for (IngestionTaskItem item : task.getItems()) {
                if (item.getStatus() == IngestionTaskItemStatus.PENDING) {
                    processItem(workspaceId, kbId, taskId, item, userId);
                }
            }
        } finally {
            refreshTask(workspaceId, kbId, taskId, userId);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void processItem(String workspaceId, String kbId, String taskId, IngestionTaskItem item, String userId) {
        DocumentAsset document = findDocument(workspaceId, kbId, item);
        try {
            if (isImage(document)) {
                processImage(workspaceId, kbId, taskId, item, document, userId);
            } else {
                processText(workspaceId, kbId, taskId, item, document, userId);
            }
            knowledgeBaseRepository.refreshDocumentStats(workspaceId, kbId, userId, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("knowledge base ingestion item failed, taskId={}, itemId={}, assetId={}: {}",
                    taskId, item.getId(), item.getAssetId(), e.getMessage());
            failItem(workspaceId, kbId, taskId, item, document, userId, e);
        }
    }

    private void processText(String workspaceId, String kbId, String taskId, IngestionTaskItem item,
                             DocumentAsset document, String userId) {
        updateRunning(workspaceId, kbId, taskId, item.getId(), IngestionStage.PARSE, STAGE_PARSE_PROGRESS, userId);
        documentAssetRepository.updateStatuses(workspaceId, kbId, document.getId(),
                DocumentParseStatus.RUNNING.name(), DocumentIndexStatus.PENDING.name(), userId, LocalDateTime.now());
        TextAssetMetadata metadata = toTextMetadata(document);
        textAssetContentLoader.enrichRemoteMetadata(metadata);
        var parserOpt = textParserRouter.route(metadata);
        if (parserOpt.isEmpty()) {
            throw new BusinessException(ApiError.TEXT_PARSER_UNAVAILABLE);
        }
        TextParseResult parseResult = parserOpt.get().parse(metadata);
        if (parseResult == null) {
            throw new BusinessException(ApiError.TEXT_PARSE_FAILED);
        }

        updateRunning(workspaceId, kbId, taskId, item.getId(), IngestionStage.CHUNK, STAGE_CHUNK_PROGRESS, userId);
        List<TextChunk> chunks = textChunkSplitter.split(metadata, parseResult);

        updateRunning(workspaceId, kbId, taskId, item.getId(), IngestionStage.EMBED, STAGE_EMBED_PROGRESS, userId);
        enrichTextEmbeddings(chunks);

        updateRunning(workspaceId, kbId, taskId, item.getId(), IngestionStage.INDEX, STAGE_INDEX_PROGRESS, userId);
        documentAssetRepository.updateStatuses(workspaceId, kbId, document.getId(),
                DocumentParseStatus.SUCCESS.name(), DocumentIndexStatus.RUNNING.name(), userId, LocalDateTime.now());
        textSegmentRepository.save(document.getId(), chunks);

        completeItem(workspaceId, kbId, taskId, item.getId(), document.getId(), chunks.size(), userId);
    }

    private void processImage(String workspaceId, String kbId, String taskId, IngestionTaskItem item,
                              DocumentAsset document, String userId) {
        requireText(document.getObjectKey(), "objectKey");
        updateRunning(workspaceId, kbId, taskId, item.getId(), IngestionStage.PARSE, STAGE_PARSE_PROGRESS, userId);
        documentAssetRepository.updateStatuses(workspaceId, kbId, document.getId(),
                DocumentParseStatus.RUNNING.name(), DocumentIndexStatus.PENDING.name(), userId, LocalDateTime.now());

        String imageInput = objectStoragePort.buildAiImageInput(document.getObjectKey());
        List<Float> embedding = embedImageWithRetry(imageInput);
        OcrStructuredResult structuredOcr = ocrPort.extractStructuredText(imageInput);
        List<String> tags = safeList(contentPort.generateTags(imageInput));
        List<GraphTriple> graph = safeList(contentPort.generateGraph(imageInput));

        updateRunning(workspaceId, kbId, taskId, item.getId(), IngestionStage.INDEX, STAGE_INDEX_PROGRESS, userId);
        documentAssetRepository.updateStatuses(workspaceId, kbId, document.getId(),
                DocumentParseStatus.SUCCESS.name(), DocumentIndexStatus.RUNNING.name(), userId, LocalDateTime.now());

        List<Segment> segments = buildImageSegments(document, embedding, structuredOcr, tags, graph);
        kbSegmentBulkWriter.write(segments);
        completeItem(workspaceId, kbId, taskId, item.getId(), document.getId(), segments.size(), userId);
    }

    private DocumentAsset findDocument(String workspaceId, String kbId, IngestionTaskItem item) {
        if (!StringUtils.hasText(item.getAssetId())) {
            throw new BusinessException(ApiError.DOCUMENT_NOT_FOUND, "Task item is not linked to a document asset.");
        }
        return documentAssetRepository.findActiveById(workspaceId, kbId, item.getAssetId())
                .orElseThrow(() -> new BusinessException(ApiError.DOCUMENT_NOT_FOUND));
    }

    private TextAssetMetadata toTextMetadata(DocumentAsset document) {
        TextAssetMetadata metadata = new TextAssetMetadata();
        metadata.setKbId(document.getKbId());
        metadata.setAssetId(document.getId());
        metadata.setTitle(StringUtils.hasText(document.getTitle()) ? document.getTitle() : document.getFileName());
        metadata.setFileName(document.getFileName());
        metadata.setMimeType(document.getMimeType());
        metadata.setObjectKey(document.getObjectKey());
        metadata.setFileHash(document.getFileHash());
        metadata.setSourceUrl(document.getSourceUrl());
        metadata.setCreatedAt(toMillis(document.getCreatedAt()));
        metadata.setUpdatedAt(System.currentTimeMillis());
        return metadata;
    }

    private List<Segment> buildImageSegments(DocumentAsset document, List<Float> embedding,
                                             OcrStructuredResult structuredOcr, List<String> tags,
                                             List<GraphTriple> graph) {
        long createdAt = toMillis(document.getCreatedAt());
        String title = StringUtils.hasText(document.getTitle()) ? document.getTitle() : document.getFileName();
        String ocrContent = structuredOcr == null ? null : structuredOcr.getFullText();
        String ocrSummary = clip(ocrContent, 180);
        List<Segment> segments = new ArrayList<>();

        segments.add(Segment.builder()
                .segmentId(document.getId() + ":caption")
                .kbId(document.getKbId())
                .assetId(document.getId())
                .assetType(KbAssetTypeEnum.IMAGE)
                .segmentType(SegmentType.IMAGE_CAPTION)
                .title(title)
                .contentText(resolveImageCaption(title, graph))
                .embedding(embedding)
                .sourceRef(document.getObjectKey())
                .thumbnail(document.getObjectKey())
                .ocrSummary(ocrSummary)
                .tags(tags)
                .createdAt(createdAt)
                .build());

        List<OcrParagraph> paragraphs = resolveParagraphs(structuredOcr, ocrContent);
        Integer imageWidth = structuredOcr == null ? null : structuredOcr.getImageWidth();
        Integer imageHeight = structuredOcr == null ? null : structuredOcr.getImageHeight();
        for (OcrParagraph paragraph : paragraphs) {
            if (!StringUtils.hasText(paragraph.getText())) {
                continue;
            }
            segments.add(Segment.builder()
                    .segmentId(document.getId() + ":ocr:" + paragraph.getIndex())
                    .kbId(document.getKbId())
                    .assetId(document.getId())
                    .assetType(KbAssetTypeEnum.IMAGE)
                    .segmentType(SegmentType.IMAGE_OCR_BLOCK)
                    .title(title)
                    .ocrText(paragraph.getText())
                    .bbox(resolveBbox(paragraph.getBbox(), imageWidth, imageHeight))
                    .imageWidth(imageWidth)
                    .imageHeight(imageHeight)
                    .sourceRef(document.getObjectKey())
                    .thumbnail(document.getObjectKey())
                    .ocrSummary(ocrSummary)
                    .tags(tags)
                    .createdAt(createdAt)
                    .build());
        }
        return segments;
    }

    private void updateRunning(String workspaceId, String kbId, String taskId, String itemId,
                               IngestionStage stage, int progress, String userId) {
        LocalDateTime now = LocalDateTime.now();
        ingestionTaskRepository.markItemRunning(workspaceId, kbId, taskId, itemId, stage.name(), progress, now);
        ingestionTaskRepository.refreshSummary(workspaceId, kbId, taskId, userId, now);
    }

    private void completeItem(String workspaceId, String kbId, String taskId, String itemId,
                              String assetId, int segmentCount, String userId) {
        LocalDateTime now = LocalDateTime.now();
        documentAssetRepository.updateIngestionResult(workspaceId, kbId, assetId,
                DocumentParseStatus.SUCCESS.name(), DocumentIndexStatus.SUCCESS.name(), segmentCount,
                null, null, userId, now);
        ingestionTaskRepository.markItemSuccess(workspaceId, kbId, taskId, itemId,
                IngestionStage.ASKABLE.name(), STAGE_DONE_PROGRESS, now);
        ingestionTaskRepository.refreshSummary(workspaceId, kbId, taskId, userId, now);
    }

    private void failItem(String workspaceId, String kbId, String taskId, IngestionTaskItem item,
                          DocumentAsset document, String userId, Exception e) {
        LocalDateTime now = LocalDateTime.now();
        String errorCode = e instanceof BusinessException businessException
                ? businessException.getError().name()
                : ApiError.INTERNAL_ERROR.name();
        String errorMessage = clip(e.getMessage(), ERROR_MESSAGE_MAX_LENGTH);
        documentAssetRepository.updateIngestionResult(workspaceId, kbId, document.getId(),
                DocumentParseStatus.FAILED.name(), DocumentIndexStatus.FAILED.name(), document.getSegmentCount(),
                errorCode, errorMessage, userId, now);
        ingestionTaskRepository.markItemFailed(workspaceId, kbId, taskId, item.getId(),
                item.getStage().name(), item.getProgress(), errorCode, errorMessage, now);
        ingestionTaskRepository.refreshSummary(workspaceId, kbId, taskId, userId, now);
    }

    private void refreshTask(String workspaceId, String kbId, String taskId, String userId) {
        ingestionTaskRepository.refreshSummary(workspaceId, kbId, taskId, userId, LocalDateTime.now());
    }

    private boolean isImage(DocumentAsset document) {
        return "IMAGE".equalsIgnoreCase(document.getFileType());
    }

    private void enrichTextEmbeddings(List<TextChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (TextChunk chunk : chunks) {
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
        return callEmbeddingWithRetry(() -> embeddingPort.embedText(text), "text");
    }

    private List<Float> embedImageWithRetry(String imageInput) {
        return callEmbeddingWithRetry(() -> embeddingPort.embedImage(imageInput), "image");
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

    private List<OcrParagraph> resolveParagraphs(OcrStructuredResult structuredOcr, String ocrContent) {
        if (structuredOcr != null && structuredOcr.getParagraphs() != null && !structuredOcr.getParagraphs().isEmpty()) {
            return structuredOcr.getParagraphs();
        }
        if (!StringUtils.hasText(ocrContent)) {
            return List.of();
        }
        return List.of(OcrParagraph.builder()
                .index(0)
                .text(ocrContent)
                .build());
    }

    private Bbox resolveBbox(OcrBoundingBox box, Integer imageWidth, Integer imageHeight) {
        if (box == null || !box.isValid()) {
            return null;
        }
        if (imageWidth != null && imageHeight != null && imageWidth > 0 && imageHeight > 0
                && (box.getX() < 0 || box.getY() < 0
                || box.getX() + box.getWidth() > imageWidth
                || box.getY() + box.getHeight() > imageHeight)) {
            return null;
        }
        return Bbox.builder()
                .x(box.getX())
                .y(box.getY())
                .width(box.getWidth())
                .height(box.getHeight())
                .unit(box.getUnit())
                .build();
    }

    private String resolveImageCaption(String title, List<GraphTriple> graph) {
        if (graph == null || graph.isEmpty()) {
            return title;
        }
        String graphText = String.join("\n", graph.stream()
                .filter(Objects::nonNull)
                .limit(5)
                .map(triple -> String.join(" ", safeText(triple.getS()), safeText(triple.getP()),
                        safeText(triple.getO())))
                .toList());
        return StringUtils.hasText(graphText) ? title + "\n" + graphText : title;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, fieldName + " cannot be blank.");
        }
        return value.trim();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private long toMillis(LocalDateTime value) {
        if (value == null) {
            return System.currentTimeMillis();
        }
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
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
