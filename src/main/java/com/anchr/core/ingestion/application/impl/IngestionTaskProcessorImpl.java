package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.application.IngestionTaskProcessor;
import com.anchr.core.ingestion.application.acl.IngestionDoclingAcl;
import com.anchr.core.ingestion.application.acl.IngestionRetrievalAcl;
import com.anchr.core.ingestion.application.acl.IngestionStorageAcl;
import com.anchr.core.ingestion.application.model.IngestionDoclingException;
import com.anchr.core.ingestion.application.model.IngestionDoclingFailureKind;
import com.anchr.core.ingestion.application.model.IngestionDoclingJob;
import com.anchr.core.ingestion.application.model.IngestionDoclingJobError;
import com.anchr.core.ingestion.application.model.IngestionIndexSegment;
import com.anchr.core.ingestion.application.model.IngestionStorageCredential;
import com.anchr.core.ingestion.application.model.IngestionStorageTarget;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.parser.DoclingChunkMapper;
import com.anchr.core.integration.ai.client.AiClient;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.search.domain.model.EmbeddingProjection;
import com.anchr.core.search.domain.model.EmbeddingProjectionPolicy;
import com.anchr.core.search.domain.model.EmbeddingProjectionPolicy.Profile;
import com.anchr.core.search.domain.model.SegmentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

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
@RequiredArgsConstructor
public class IngestionTaskProcessorImpl implements IngestionTaskProcessor {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;
    private static final Duration DEFAULT_PARSE_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration DEFAULT_PARSE_TIMEOUT = Duration.ofMinutes(45);

    private final Set<String> locallyDispatchedItems = ConcurrentHashMap.newKeySet();
    private final Object embeddingPaceLock = new Object();
    private long nextEmbeddingCallAt;

    @Qualifier("ingestionTaskExecutor")
    private final Executor ingestionTaskExecutor;
    private final IngestionTaskRepository ingestionTaskRepository;
    private final AssetRepository assetRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final IngestionEmbeddingPort embeddingPort;
    private final AesUtil aesUtil;
    private final IngestionIndexFinalizer ingestionIndexFinalizer;
    private final IngestionRetrievalAcl ingestionRetrievalAcl;
    private final IngestionStageTransactionCoordinator transactionCoordinator;
    private final IngestionObjectStoragePort objectStoragePort;
    private final IngestionStorageAcl ingestionStorageAcl;
    private final DoclingChunkMapper doclingChunkMapper;
    private final IngestionDoclingAcl ingestionDoclingAcl;
    private final ObjectMapper objectMapper;
    private final IdGen idGen;

    @Value("${app.ingestion.claim-batch-size:32}")
    private int claimBatchSize = 32;

    @Value("${app.ingestion.parse-poll-interval:2s}")
    private Duration parsePollInterval = DEFAULT_PARSE_POLL_INTERVAL;

    @Value("${app.ingestion.parse-stage-timeout:45m}")
    private Duration parseTimeout = DEFAULT_PARSE_TIMEOUT;

    @Value("${app.ingestion.stage-max-retries:5}")
    private int providerMaxRetries = 5;

    @Value("${app.embedding.ingestion-min-interval-ms:1500}")
    private long embeddingMinIntervalMs = 1500;

    @Value("${app.embedding.ingestion-rate-limit-max-attempts:5}")
    private int embeddingRateLimitMaxAttempts = 5;

    @Value("${app.embedding.ingestion-rate-limit-backoff-ms:5000}")
    private long embeddingRateLimitBackoffMs = 5000;

    @Value("${app.docling.embedded-image-upload-enabled:false}")
    private boolean embeddedImageUploadEnabled;

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

    @Scheduled(fixedDelayString = "${app.ingestion.poll-interval-ms:1000}",
            initialDelayString = "${app.ingestion.poll-initial-delay-ms:1000}")
    public void pollPendingItems() {
        try {
            dispatch(ingestionTaskRepository.listPendingItemIds(effectiveBatchSize()));
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
                    taskId, effectiveBatchSize()));
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
        try {
            item = transactionCoordinator.ensureTargetIndexGeneration(item);
            asset = findAsset(item);
            if (!transactionCoordinator.updateAssetStatus(
                    item, asset,
                    DocumentParseStatus.RUNNING.name(),
                    DocumentIndexStatus.PENDING.name())) {
                return;
            }

            ParseRunContext parseContext = createParseContext(item, asset);
            ParsedJob parsedJob = parseDocument(parseContext, asset);
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

            List<IngestionIndexSegment> segments = prepareSegments(item, asset, parsedJob.result());
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

            var writeReceipt = ingestionRetrievalAcl.replaceGeneration(item, asset, segments);
            if (!ingestionIndexFinalizer.activateGeneration(
                    item, asset, IngestionIndexFinalizer.countReadableSegments(segments),
                    writeReceipt)) return;
            acknowledgeBestEffort(doclingJobId);
            refreshKnowledgeBaseStats(item);
        } catch (WorkerInterruptedException interrupted) {
            failItem(item, asset, ApiError.INTERNAL_ERROR,
                    "文档处理线程被中断，请重新执行。", doclingJobId);
        } catch (BusinessException businessFailure) {
            failItem(item, asset, businessFailure.getError(),
                    businessFailure.getMessage(), doclingJobId);
        } catch (RuntimeException exception) {
            ApiError error = exception instanceof EmbeddingCallException
                    ? ApiError.EMBEDDING_FAILED : ApiError.INTERNAL_ERROR;
            failItem(item, asset, error, exception.getMessage(), doclingJobId);
        }
    }

    private ParseRunContext createParseContext(IngestionTaskItem item, Asset asset) {
        long generation = requireTargetIndexGeneration(item);
        IngestionStorageTarget storageTarget = embeddedImageUploadEnabled
                ? ingestionStorageAcl.findTarget(asset.getId(), generation).orElse(null)
                : null;
        IngestionParseRequestTemplate template = IngestionParseRequestTemplate.capture(
                asset, embeddedImageUploadEnabled, storageTarget).validated();
        return new ParseRunContext(
                IngestionParseIdentity.requestId(item.getTaskId(), item.getId(), generation),
                IngestionParseIdentity.sourceRevision(asset),
                asset.getId(),
                generation,
                template);
    }

    private ParsedJob parseDocument(ParseRunContext context, Asset asset) {
        Instant deadline = Instant.now().plus(effectiveParseTimeout());
        int recoveries = 0;
        String jobId = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                IngestionDoclingJob job;
                if (!StringUtils.hasText(jobId)) {
                    job = ingestionDoclingAcl.submitJob(buildParseRequest(context, asset));
                    jobId = job.jobId();
                } else {
                    job = ingestionDoclingAcl.getJob(jobId, context.requestId());
                }
                switch (job.normalizedStatus()) {
                    case "succeeded" -> {
                        if (job.result() == null) {
                            throw new BusinessException(
                                    ApiError.TEXT_PARSE_FAILED,
                                    "Docling succeeded without a parse result.");
                        }
                        return new ParsedJob(jobId, job.result());
                    }
                    case "queued", "running" -> sleep(effectiveParsePollInterval());
                    case "failed" -> {
                        if (!isRetryable(job.error()) || recoveries >= effectiveProviderMaxRetries()) {
                            throw doclingJobFailure(job.error());
                        }
                        acknowledgeFailedJob(jobId);
                        jobId = null;
                        recoveries++;
                        sleep(retryDelay(recoveries));
                    }
                    default -> throw new BusinessException(
                            ApiError.TEXT_PARSE_FAILED,
                            "Docling returned unknown job status: " + clip(job.status(), 128));
                }
            } catch (IngestionDoclingException failure) {
                if (failure.kind() == IngestionDoclingFailureKind.NOT_FOUND
                        && recoveries < effectiveProviderMaxRetries()) {
                    jobId = null;
                    recoveries++;
                    sleep(retryDelay(recoveries));
                    continue;
                }
                if (failure.kind() == IngestionDoclingFailureKind.TRANSIENT
                        && recoveries < effectiveProviderMaxRetries()) {
                    recoveries++;
                    sleep(positiveDuration(failure.retryAfter())
                            ? failure.retryAfter() : retryDelay(recoveries));
                    continue;
                }
                throw new BusinessException(
                        ApiError.TEXT_PARSE_FAILED, failure.getMessage(), failure);
            }
        }
        throw new BusinessException(
                ApiError.TEXT_PARSE_FAILED,
                "Docling parse exceeded " + effectiveParseTimeout() + ".");
    }

    private ParseRequest buildParseRequest(ParseRunContext context, Asset asset) {
        return context.template().toRequest(
                context.requestId(), context.sourceRevision(), resolveSourceUrl(asset),
                buildEncryptedOssCredentials(context));
    }

    private Map<String, String> buildEncryptedOssCredentials(ParseRunContext context) {
        IngestionParseRequestTemplate template = context.template();
        if (template.ossTarget() == null) return null;
        try {
            IngestionStorageCredential credential =
                    ingestionStorageAcl.issueTemporaryCredential(
                            template.ossTarget(),
                            context.assetId(),
                            context.targetGeneration());
            Map<String, Object> token = credential.toCredentialMap();
            String aad = String.join("\n",
                    context.requestId(), template.ossTarget().bucket(),
                    template.ossTarget().basePath(), template.ossTarget().endpoint());
            AesUtil.AeadEnvelope envelope = aesUtil.encryptAead(
                    objectMapper.writeValueAsString(token), aad);
            return Map.of(
                    "version", "1",
                    "keyId", "app-security-v1",
                    "nonce", envelope.nonce(),
                    "ciphertext", envelope.ciphertext(),
                    "tag", envelope.tag(),
                    "expiration", Objects.toString(token.get("expiration"), ""));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "Failed to issue temporary Docling output credentials.", exception);
        }
    }

    private List<IngestionIndexSegment> prepareSegments(
            IngestionTaskItem item, Asset asset, ParseResponse parsed) {
        boolean parsedContentIsEmpty = parsed.chunks() == null || parsed.chunks().isEmpty();
        long generation = requireTargetIndexGeneration(item);
        List<Chunk> chunks = doclingChunkMapper.toTextChunks(asset, parsed, generation);
        if (chunks == null) chunks = List.of();
        List<Chunk> embeddedImages = doclingChunkMapper.toDocumentImageChunks(
                asset, parsed, generation);
        if (!embeddedImages.isEmpty()) {
            List<Chunk> combined = new ArrayList<>(chunks.size() + embeddedImages.size());
            combined.addAll(chunks);
            combined.addAll(embeddedImages);
            chunks = List.copyOf(combined);
        }
        if (chunks.isEmpty() && !isImage(asset)) {
            throw new BusinessException(
                    ApiError.TEXT_PARSE_FAILED,
                    parsedContentIsEmpty
                            ? "Docling returned no usable text or embedded images."
                            : "Docling returned no usable chunks.");
        }

        Profile profile = Profile.fromMulti(embeddingPort.isMulti());
        List<IngestionIndexSegment> segments = buildSegments(asset, chunks, generation, profile);
        String imageInput = EmbeddingProjectionPolicy.requiresImageVisual(
                profile, asset.getFileType()) ? resolveImageEmbeddingUrl(asset) : null;
        return applyEmbeddings(asset, segments, profile, imageInput);
    }

    private List<IngestionIndexSegment> buildSegments(
            Asset asset, List<Chunk> chunks, long generation, Profile profile) {
        long createdAt = System.currentTimeMillis();
        List<IngestionIndexSegment> segments = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (chunk == null || !StringUtils.hasText(chunk.getSegmentId())) continue;
            SegmentType segmentType = chunk.getSegmentType() != null
                    ? chunk.getSegmentType()
                    : isImage(asset) ? SegmentType.IMAGE_OCR_BLOCK : SegmentType.TEXT_CHUNK;
            segments.add(new IngestionIndexSegment(
                    chunk.getSegmentId(), chunk.getKbId(), asset.getId(), generation,
                    asset.getFileType(), segmentType.name(), chunk.getTitle(),
                    chunk.getChunkText(), chunk.getOcrText(), chunk.getPageNo(),
                    chunk.getChunkOrder(), chunk.getBboxInfos(), chunk.getImageWidth(),
                    chunk.getImageHeight(), null, chunk.getSourceRef(), null, null,
                    null, createdAt));
        }
        if (EmbeddingProjectionPolicy.requiresImageVisual(profile, asset.getFileType())) {
            segments.add(new IngestionIndexSegment(
                    idGen.nextIdStr(), asset.getKbId(), asset.getId(), generation,
                    asset.getFileType(), SegmentType.IMAGE_VISUAL.name(),
                    StringUtils.hasText(asset.getTitle()) ? asset.getTitle() : asset.getFileName(),
                    null, null, null, 0, null, null, null, null,
                    stableSourceRef(asset), null, null, null, createdAt));
        }
        return segments;
    }

    private List<IngestionIndexSegment> applyEmbeddings(
            Asset asset,
            List<IngestionIndexSegment> segments,
            Profile profile,
            String imageInput) {
        List<IngestionIndexSegment> embedded = new ArrayList<>(segments.size());
        for (IngestionIndexSegment segment : segments) {
            SegmentType segmentType = SegmentType.valueOf(segment.segmentType());
            String imageSource = switch (segmentType) {
                case IMAGE_VISUAL -> imageInput;
                case DOCUMENT_IMAGE -> StringUtils.hasText(segment.sourceRef())
                        ? objectStoragePort.buildImageEmbeddingUrl(segment.sourceRef()) : null;
                default -> null;
            };
            Optional<EmbeddingProjection> projection = EmbeddingProjectionPolicy.select(
                    profile, asset.getFileType(), segmentType,
                    segment.contentText(), segment.ocrText(), imageSource);
            if (projection.isEmpty()) {
                embedded.add(segment.withEmbedding(null));
                continue;
            }
            EmbeddingProjection selected = projection.get();
            List<Float> embedding = embed(selected.source(), selected.inputType().requestValue());
            if (embedding == null || embedding.isEmpty()) {
                throw new BusinessException(ApiError.EMBEDDING_RESULT_EMPTY);
            }
            embedded.add(segment.withEmbedding(embedding));
        }
        return embedded;
    }

    private List<Float> embed(String input, String inputType) {
        int attempts = Math.max(1, embeddingRateLimitMaxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            reserveEmbeddingCallSlot();
            try {
                return embeddingPort.embed(input, inputType);
            } catch (BusinessException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                if (!isRateLimitError(exception) || attempt == attempts) {
                    throw new EmbeddingCallException(exception);
                }
                sleep(Duration.ofMillis(resolveEmbeddingBackoffMs(attempt)));
            }
        }
        throw new IllegalStateException("Embedding retry loop terminated unexpectedly.");
    }

    private void failItem(IngestionTaskItem item,
                          Asset asset,
                          ApiError error,
                          String message,
                          String jobId) {
        if (item == null) return;
        String safeMessage = clip(
                StringUtils.hasText(message) ? message : error.getMessage(),
                ERROR_MESSAGE_MAX_LENGTH);
        boolean failed = transactionCoordinator.failRunning(
                item, asset, error, safeMessage,
                DocumentParseStatus.FAILED.name(), DocumentIndexStatus.FAILED.name());
        if (!failed) return;
        acknowledgeBestEffort(jobId);
        log.warn("knowledge-base ingestion item failed, taskId={}, itemId={}, stage={}, errorCode={}, error={}",
                item.getTaskId(), item.getId(), item.getStage(), error, safeMessage);
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
                || !StringUtils.hasText(item.getAssetId())) return null;
        try {
            return assetRepository.findActiveById(item.getKbId(), item.getAssetId()).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String resolveSourceUrl(Asset asset) {
        if (StringUtils.hasText(asset.getObjectKey())) {
            return objectStoragePort.buildDownloadUrl(asset.getObjectKey());
        }
        throw new BusinessException(
                ApiError.TEXT_PARSE_FAILED, "Document has no source object key.");
    }

    private String stableSourceRef(Asset asset) {
        if (StringUtils.hasText(asset.getObjectKey())) return asset.getObjectKey().trim();
        throw new BusinessException(
                ApiError.TEXT_PARSE_FAILED, "Document has no source object key.");
    }

    private String resolveImageEmbeddingUrl(Asset asset) {
        if (StringUtils.hasText(asset.getObjectKey())) {
            return objectStoragePort.buildImageEmbeddingUrl(asset.getObjectKey().trim());
        }
        return resolveSourceUrl(asset);
    }

    private long requireTargetIndexGeneration(IngestionTaskItem item) {
        Long generation = item.getTargetIndexGeneration();
        if (generation == null || generation < 1L) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "Ingestion item has no valid target index generation.");
        }
        return generation;
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

    private boolean isRetryable(IngestionDoclingJobError error) {
        if (error == null || !StringUtils.hasText(error.code())) return false;
        String code = error.code().trim().toUpperCase(Locale.ROOT);
        return "QUEUE_TIMEOUT".equals(code)
                || "INTERNAL_ERROR".equals(code)
                || "SOURCE_DOWNLOAD_ERROR".equals(code);
    }

    private BusinessException doclingJobFailure(IngestionDoclingJobError error) {
        String message = error == null
                ? "Docling job failed."
                : "Docling job failed [" + clip(error.code(), 80) + "]: "
                        + clip(error.message(), 300);
        return new BusinessException(ApiError.TEXT_PARSE_FAILED, message);
    }

    private void acknowledgeFailedJob(String jobId) {
        try {
            ingestionDoclingAcl.ackJob(jobId);
        } catch (IngestionDoclingException exception) {
            throw new BusinessException(
                    ApiError.TEXT_PARSE_FAILED,
                    "Failed to release the unsuccessful Docling job: " + exception.getMessage(),
                    exception);
        }
    }

    private void acknowledgeBestEffort(String jobId) {
        if (!StringUtils.hasText(jobId)) return;
        try {
            ingestionDoclingAcl.ackJob(jobId);
        } catch (RuntimeException exception) {
            log.warn("Docling ACK failed after ingestion terminal state, jobId={}: {}",
                    jobId, exception.getMessage());
        }
    }

    private void reserveEmbeddingCallSlot() {
        long waitMs;
        synchronized (embeddingPaceLock) {
            long now = System.currentTimeMillis();
            waitMs = Math.max(0L, nextEmbeddingCallAt - now);
            nextEmbeddingCallAt = Math.max(now, nextEmbeddingCallAt)
                    + Math.max(0L, embeddingMinIntervalMs);
        }
        if (waitMs > 0L) sleep(Duration.ofMillis(waitMs));
    }

    private void sleep(Duration duration) {
        if (!positiveDuration(duration)) return;
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WorkerInterruptedException(exception);
        }
    }

    private boolean isRateLimitError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AiClient.OpenAiException failure
                    && failure.statusCode() == 429) return true;
            String message = current.getMessage();
            if (StringUtils.hasText(message)) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (message.contains("429") || message.contains("Throttling")
                        || message.contains("RateQuota") || lower.contains("rate limit")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private long resolveEmbeddingBackoffMs(int attempt) {
        long base = Math.max(1000L, embeddingRateLimitBackoffMs);
        long multiplier = 1L << Math.min(Math.max(0, attempt - 1), 4);
        return base * multiplier;
    }

    private Duration retryDelay(int retryCount) {
        long seconds = Math.min(60L, 1L << Math.min(Math.max(0, retryCount - 1), 6));
        return Duration.ofSeconds(seconds);
    }

    private boolean positiveDuration(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private int effectiveBatchSize() {
        return Math.max(1, claimBatchSize);
    }

    private int effectiveProviderMaxRetries() {
        return Math.max(0, providerMaxRetries);
    }

    private Duration effectiveParsePollInterval() {
        return positiveDuration(parsePollInterval)
                ? parsePollInterval : DEFAULT_PARSE_POLL_INTERVAL;
    }

    private Duration effectiveParseTimeout() {
        return positiveDuration(parseTimeout) ? parseTimeout : DEFAULT_PARSE_TIMEOUT;
    }

    private boolean isImage(Asset asset) {
        return "IMAGE".equalsIgnoreCase(asset.getFileType());
    }

    private String updatedBy(IngestionTaskItem item) {
        return StringUtils.hasText(item.getTaskCreatedBy())
                ? item.getTaskCreatedBy() : "ingestion-worker";
    }

    private String clip(String text, int maxLength) {
        if (!StringUtils.hasText(text)) return null;
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static final class WorkerInterruptedException extends RuntimeException {
        private WorkerInterruptedException(Throwable cause) {
            super(cause);
        }
    }

    private static final class EmbeddingCallException extends RuntimeException {
        private EmbeddingCallException(Throwable cause) {
            super(cause == null ? null : cause.getMessage(), cause);
        }
    }

    private record ParseRunContext(
            String requestId,
            String sourceRevision,
            String assetId,
            long targetGeneration,
            IngestionParseRequestTemplate template) {
    }

    private record ParsedJob(String jobId, ParseResponse result) {
    }
}
