package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.ingestion.application.IngestionTaskProcessor;
import com.anchr.core.ingestion.application.artifact.IngestionArtifactException;
import com.anchr.core.ingestion.application.artifact.IngestionArtifactStore;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.IngestionClaimContext;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.ingestion.infrastructure.parser.DoclingChunkMapper;
import com.anchr.core.integration.ai.client.AiClient;
import com.anchr.core.integration.ai.client.DoclingClient;
import com.anchr.core.integration.ai.client.DoclingClient.DoclingClientException;
import com.anchr.core.integration.ai.client.DoclingClient.DoclingJob;
import com.anchr.core.integration.ai.client.DoclingClient.DoclingJobError;
import com.anchr.core.integration.storage.StorageTokenIssuer;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.anchr.core.settings.domain.model.StorageConfig;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
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
 * Database-driven, restart-safe ingestion stage scheduler.
 *
 * <p>Each worker owns one fenced database claim and performs at most one durable stage. No
 * correctness decision depends on this JVM's queues, locks or process lifetime.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionTaskProcessorImpl implements IngestionTaskProcessor {

    private static final int STAGE_PARSE_PROGRESS = 20;
    private static final int STAGE_EMBED_PROGRESS = 55;
    private static final int STAGE_INDEX_PROGRESS = 75;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;
    private static final Duration DEFAULT_PARSE_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration DEFAULT_PARSE_STAGE_TIMEOUT = Duration.ofMinutes(45);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
    private final StorageTokenIssuer storageTokenIssuer;
    private final IngestionIndexFinalizer ingestionIndexFinalizer;
    private final IngestionStageTransactionCoordinator transactionCoordinator;
    private final SegmentRepository segmentRepository;
    private final IngestionObjectStoragePort objectStoragePort;
    private final StorageConfigRepository storageConfigRepository;
    private final DoclingChunkMapper doclingChunkMapper;
    private final DoclingClient doclingClient;
    private final IngestionArtifactStore artifactStore;
    private final ObjectMapper objectMapper;

    @Value("${app.ingestion.claim-batch-size:32}")
    private int claimBatchSize = 32;

    @Value("${app.ingestion.claim-lease-seconds:300}")
    private long claimLeaseSeconds = 300;

    @Value("${app.ingestion.parse-poll-interval:2s}")
    private Duration parsePollInterval = DEFAULT_PARSE_POLL_INTERVAL;

    @Value("${app.ingestion.parse-stage-timeout:45m}")
    private Duration parseStageTimeout = DEFAULT_PARSE_STAGE_TIMEOUT;

    @Value("${app.ingestion.stage-max-retries:5}")
    private int stageMaxRetries = 5;

    @Value("${app.embedding.ingestion-min-interval-ms:1500}")
    private long embeddingMinIntervalMs = 1500;

    @Value("${app.embedding.ingestion-rate-limit-max-attempts:5}")
    private int embeddingRateLimitMaxAttempts = 5;

    @Value("${app.embedding.ingestion-rate-limit-backoff-ms:5000}")
    private long embeddingRateLimitBackoffMs = 5000;

    @Value("${app.docling.embedded-image-upload-enabled:false}")
    private boolean embeddedImageUploadEnabled;

    @Scheduled(fixedDelayString = "${app.ingestion.poll-interval-ms:1000}",
            initialDelayString = "${app.ingestion.poll-initial-delay-ms:1000}")
    public void pollDueItems() {
        try {
            dispatch(ingestionTaskRepository.listClaimableItemIds(effectiveBatchSize()));
        } catch (RuntimeException e) {
            log.warn("failed to scan claimable ingestion items: {}", e.getMessage(), e);
        }
    }

    /**
     * Fast wake-up after a transaction commits. The scheduled database scan remains the
     * authoritative recovery mechanism if this hint is rejected or the process exits.
     */
    @Override
    public void submit(String kbId, String taskId, String userId) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        try {
            dispatch(ingestionTaskRepository.listClaimableItemIds(taskId, effectiveBatchSize()));
        } catch (RuntimeException e) {
            log.debug("ingestion fast wake-up deferred to scheduler, taskId={}, reason={}",
                    taskId, e.getMessage());
        }
    }

    private void dispatch(List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return;
        }
        for (String itemId : itemIds) {
            if (!StringUtils.hasText(itemId) || !locallyDispatchedItems.add(itemId)) {
                continue;
            }
            try {
                ingestionTaskExecutor.execute(() -> processCandidate(itemId));
            } catch (RejectedExecutionException rejected) {
                locallyDispatchedItems.remove(itemId);
                log.debug("ingestion executor saturated; item remains claimable, itemId={}", itemId);
            } catch (RuntimeException e) {
                locallyDispatchedItems.remove(itemId);
                log.warn("failed to dispatch ingestion item, itemId={}: {}", itemId, e.getMessage());
            }
        }
    }

    private void processCandidate(String itemId) {
        Optional<IngestionTaskItem> claim;
        try {
            claim = ingestionTaskRepository.claimOne(itemId, effectiveLeaseSeconds());
        } catch (RuntimeException e) {
            log.warn("failed to claim ingestion item, itemId={}: {}",
                    itemId, e.getMessage(), e);
            return;
        } finally {
            // This set only suppresses duplicate executor submissions before the DB claim.
            // Remove it before external work so an expired lease can be reclaimed by this same
            // instance even if the previous worker is stuck in a provider call.
            locallyDispatchedItems.remove(itemId);
        }
        claim.ifPresent(this::processClaim);
    }

    void processClaim(IngestionTaskItem item) {
        if (item == null || item.getExecutionStage() == null) {
            return;
        }
        try {
            if (item.getStageRetryCount() > effectiveStageMaxRetries()) {
                failClaim(item, tryFindAsset(item), ApiError.INTERNAL_ERROR,
                        "Ingestion stage exceeded its recovery-attempt limit.");
                return;
            }
            switch (item.getExecutionStage()) {
                case PARSE_SUBMIT -> processParseSubmit(item);
                case PARSE_WAIT -> processParseWait(item);
                case PARSE_PERSIST -> processParsePersist(item);
                case EMBED -> processEmbed(item);
                case INDEX -> processIndex(item);
                case COMPLETE, FAILED -> log.debug(
                        "terminal ingestion item was unexpectedly claimed, itemId={}, stage={}",
                        item.getId(), item.getExecutionStage());
            }
        } catch (StaleClaimException stale) {
            log.debug("stale ingestion worker stopped, itemId={}, stage={}, attempt={}",
                    item.getId(), item.getExecutionStage(), item.getStageAttempt());
        } catch (RuntimeException e) {
            handleClaimFailure(item, tryFindAsset(item), e);
        }
    }

    private void processParseSubmit(IngestionTaskItem claimedItem) {
        Asset asset = findAsset(claimedItem);
        IngestionTaskItem item = ensureParseContext(claimedItem, asset);
        try {
            if (!transactionCoordinator.updateAssetStatusForCurrentClaim(
                    item,
                    asset,
                    DocumentParseStatus.RUNNING.name(),
                    DocumentIndexStatus.PENDING.name())) {
                throw new StaleClaimException();
            }

            ParseRequest request = buildParseRequest(item, asset);
            DoclingJob job = doclingClient.submitJob(request);
            IngestionTaskItem withJob = item.toBuilder().doclingJobId(job.jobId()).build();
            switch (job.normalizedStatus()) {
                case "queued", "running" -> transitionOrStop(runningTransition(
                        withJob,
                        IngestionExecutionStage.PARSE_WAIT,
                        LocalDateTime.now().plus(effectiveParsePollInterval()),
                        item.getStageRetryCount(),
                        IngestionStage.PARSE,
                        STAGE_PARSE_PROGRESS));
                case "succeeded" -> transitionOrStop(runningTransition(
                        withJob,
                        IngestionExecutionStage.PARSE_PERSIST,
                        LocalDateTime.now(),
                        item.getStageRetryCount(),
                        IngestionStage.PARSE,
                        STAGE_PARSE_PROGRESS));
                case "failed" -> handleFailedDoclingJob(withJob, asset, job.error());
                default -> failClaim(item, asset, ApiError.TEXT_PARSE_FAILED,
                        "Docling returned unknown job status: " + clip(job.status(), 128));
            }
        } catch (StaleClaimException stale) {
            throw stale;
        } catch (RuntimeException e) {
            // ensureParseContext may have durably filled the v2 identity and request snapshot.
            // Every retry transition after that point must copy the prepared item, never the
            // stale pre-context claim that could clear those fields.
            try {
                handleClaimFailure(item, asset, e);
            } catch (StaleClaimException stale) {
                throw stale;
            } catch (RuntimeException transitionFailure) {
                // Do not fall back to the pre-context item. Leaving the prepared claim in place
                // lets the DB lease expire and preserves the stable request fingerprint.
                log.warn("failed to persist prepared parse retry, itemId={}, requestId={}: {}",
                        item.getId(), item.getDoclingRequestId(), transitionFailure.getMessage(),
                        transitionFailure);
            }
        }
    }

    private void processParseWait(IngestionTaskItem item) {
        Asset asset = findAsset(item);
        requireParseJob(item);
        if (parseStageExpired(item)) {
            failClaim(item, asset, ApiError.TEXT_PARSE_FAILED,
                    "Docling parse stage exceeded " + effectiveParseStageTimeout() + ".");
            return;
        }

        DoclingJob job = doclingClient.getJob(item.getDoclingJobId(), item.getDoclingRequestId());
        switch (job.normalizedStatus()) {
            case "queued", "running" -> transitionOrStop(runningTransition(
                    item,
                    IngestionExecutionStage.PARSE_WAIT,
                    LocalDateTime.now().plus(effectiveParsePollInterval()),
                    item.getStageRetryCount(),
                    IngestionStage.PARSE,
                    STAGE_PARSE_PROGRESS));
            case "succeeded" -> transitionOrStop(runningTransition(
                    item,
                    IngestionExecutionStage.PARSE_PERSIST,
                    LocalDateTime.now(),
                    item.getStageRetryCount(),
                    IngestionStage.PARSE,
                    STAGE_PARSE_PROGRESS));
            case "failed" -> handleFailedDoclingJob(item, asset, job.error());
            default -> failClaim(item, asset, ApiError.TEXT_PARSE_FAILED,
                    "Docling returned unknown job status: " + clip(job.status(), 128));
        }
    }

    private void processParsePersist(IngestionTaskItem item) {
        Asset asset = findAsset(item);
        if (StringUtils.hasText(item.getParseResultObjectKey())) {
            transitionOrStop(runningTransition(
                    item,
                    IngestionExecutionStage.EMBED,
                    LocalDateTime.now(),
                    0,
                    IngestionStage.EMBED,
                    STAGE_EMBED_PROGRESS));
            acknowledgeBestEffort(item.getDoclingJobId());
            return;
        }

        requireParseJob(item);
        DoclingJob job = doclingClient.getJob(item.getDoclingJobId(), item.getDoclingRequestId());
        switch (job.normalizedStatus()) {
            case "succeeded" -> persistParseResultAndAdvance(item, job);
            case "queued", "running" -> transitionOrStop(runningTransition(
                    item,
                    IngestionExecutionStage.PARSE_WAIT,
                    LocalDateTime.now().plus(effectiveParsePollInterval()),
                    item.getStageRetryCount(),
                    IngestionStage.PARSE,
                    STAGE_PARSE_PROGRESS));
            case "failed" -> handleFailedDoclingJob(item, asset, job.error());
            default -> failClaim(item, asset, ApiError.TEXT_PARSE_FAILED,
                    "Docling returned unknown job status: " + clip(job.status(), 128));
        }
    }

    private void persistParseResultAndAdvance(IngestionTaskItem item, DoclingJob job) {
        ParseResponse result = job.result();
        if (result == null) {
            failClaim(item, tryFindAsset(item), ApiError.TEXT_PARSE_FAILED,
                    "Docling succeeded without a parse result.");
            return;
        }
        String objectKey = artifactStore.writeParseResult(item, job.jobId(), result);
        IngestionTaskItem withArtifact = item.toBuilder()
                .parseResultObjectKey(objectKey)
                .build();
        boolean transitioned = ingestionTaskRepository.transitionClaim(runningTransition(
                withArtifact,
                IngestionExecutionStage.EMBED,
                LocalDateTime.now(),
                0,
                IngestionStage.EMBED,
                STAGE_EMBED_PROGRESS));
        if (!transitioned) {
            // The immutable object may become an orphan, but a stale worker must not ACK the
            // winner's only recoverable Docling result.
            throw new StaleClaimException();
        }
        acknowledgeBestEffort(job.jobId());
    }

    private void processEmbed(IngestionTaskItem item) {
        Asset asset = findAsset(item);
        ParseResponse parsed = artifactStore.readParseResult(item);
        if (parsed.chunks() == null || parsed.chunks().isEmpty()) {
            throw new BusinessException(
                    ApiError.TEXT_PARSE_FAILED, "Docling returned empty chunks.");
        }
        List<Chunk> chunks = doclingChunkMapper.toTextChunks(asset, parsed);
        if (chunks == null || chunks.isEmpty()) {
            throw new BusinessException(
                    ApiError.TEXT_PARSE_FAILED, "Docling returned no usable chunks.");
        }

        String imageInput = isImage(asset) ? resolveSourceUrl(asset, item) : null;
        enrichTextEmbeddings(item, asset, chunks, imageInput);
        assertCurrentClaim(item);
        String embeddingObjectKey = artifactStore.writeEmbeddingResult(item, chunks);
        IngestionTaskItem withArtifact = item.toBuilder()
                .embeddingResultObjectKey(embeddingObjectKey)
                .build();
        IngestionClaimTransition transition = runningTransition(
                withArtifact,
                IngestionExecutionStage.INDEX,
                LocalDateTime.now(),
                0,
                IngestionStage.INDEX,
                STAGE_INDEX_PROGRESS);
        boolean transitioned = transactionCoordinator.transitionAndUpdateAssetStatus(
                transition,
                asset,
                DocumentParseStatus.SUCCESS.name(),
                DocumentIndexStatus.RUNNING.name());
        if (!transitioned) {
            throw new StaleClaimException();
        }
    }

    private void processIndex(IngestionTaskItem item) {
        Asset asset = findAsset(item);
        List<Chunk> chunks = artifactStore.readEmbeddingResult(item);
        List<Segment> segments = buildSegments(asset, chunks);
        boolean indexed = ingestionIndexFinalizer.finalizeIndex(
                item, asset, segments, chunks.size());
        if (!indexed) {
            return;
        }
        cleanupOverwrittenAsset(item.getKbId(), item, updatedBy(item));
        try {
            knowledgeBaseRepository.refreshDocumentStats(
                    item.getKbId(), updatedBy(item), true);
        } catch (RuntimeException e) {
            // Index and item completion are already committed. Stats are derived data and must
            // never drive the completed claim backwards.
            log.warn("failed to refresh knowledge-base stats after ingestion, kbId={}, itemId={}: {}",
                    item.getKbId(), item.getId(), e.getMessage());
        }
    }

    private IngestionTaskItem ensureParseContext(IngestionTaskItem item, Asset asset) {
        int parseAttempt = Math.max(IngestionParseIdentity.INITIAL_ATTEMPT, item.getParseAttempt());
        String expectedRequestId = IngestionParseIdentity.requestId(
                item.getTaskId(), item.getId(), parseAttempt);
        String requestId = StringUtils.hasText(item.getDoclingRequestId())
                ? item.getDoclingRequestId() : expectedRequestId;
        if (!expectedRequestId.equals(requestId)) {
            throw new BusinessException(
                    ApiError.TEXT_PARSE_FAILED, "Persisted Docling request identity is invalid.");
        }
        String sourceRevision = StringUtils.hasText(item.getSourceRevision())
                ? item.getSourceRevision() : IngestionParseIdentity.sourceRevision(asset);

        String snapshotJson = item.getParseRequestSnapshot();
        if (!StringUtils.hasText(snapshotJson)) {
            StorageConfig storageConfig = embeddedImageUploadEnabled
                    ? storageConfigRepository.find().orElse(null) : null;
            snapshotJson = encodeSnapshot(IngestionParseRequestSnapshot.capture(
                    asset, embeddedImageUploadEnabled, storageConfig));
        }

        IngestionTaskItem prepared = item.toBuilder()
                .parseAttempt(parseAttempt)
                .doclingRequestId(requestId)
                .sourceRevision(sourceRevision)
                .parseRequestSnapshot(snapshotJson)
                .build();
        boolean changed = parseAttempt != item.getParseAttempt()
                || !Objects.equals(requestId, item.getDoclingRequestId())
                || !Objects.equals(sourceRevision, item.getSourceRevision())
                || !Objects.equals(snapshotJson, item.getParseRequestSnapshot());
        if (!changed) {
            return prepared;
        }
        boolean updated;
        try {
            updated = ingestionTaskRepository.updateClaimContext(
                    IngestionClaimContext.builder()
                            .itemId(item.getId())
                            .executionEpoch(item.getExecutionEpoch())
                            .expectedExecutionStage(item.getExecutionStage())
                            .stageAttempt(item.getStageAttempt())
                            .leaseToken(item.getLeaseToken())
                            .parseAttempt(parseAttempt)
                            .doclingRequestId(requestId)
                            .doclingJobId(item.getDoclingJobId())
                            .sourceRevision(sourceRevision)
                            .parseRequestSnapshot(snapshotJson)
                            .build());
        } catch (RuntimeException ambiguousWrite) {
            // The database may have committed even when the client lost the response. A
            // transition built from the pre-context item could clear the stable v2 fingerprint,
            // so leave the claim untouched and let a later lease holder read the row.
            throw new AmbiguousContextWriteException(ambiguousWrite);
        }
        if (!updated) {
            throw new StaleClaimException();
        }
        return prepared;
    }

    private ParseRequest buildParseRequest(IngestionTaskItem item, Asset asset) {
        IngestionParseRequestSnapshot snapshot = decodeSnapshot(item.getParseRequestSnapshot());
        String sourceUrl = resolveSourceUrl(asset, item);
        return snapshot.toRequest(
                item.getDoclingRequestId(),
                item.getSourceRevision(),
                sourceUrl,
                buildEncryptedOssCredentials(snapshot));
    }

    private Map<String, String> buildEncryptedOssCredentials(
            IngestionParseRequestSnapshot snapshot) {
        if (snapshot.ossTarget() == null) {
            return null;
        }
        StorageConfig config = storageConfigRepository.find()
                .orElseThrow(() -> new BusinessException(
                        ApiError.INTERNAL_ERROR,
                        "The persisted Docling output target no longer has storage credentials."));
        if (!snapshot.targets(config)) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "The storage output target changed during the current parse attempt.");
        }
        try {
            String accessKey = aesUtil.decrypt(config.getAccessKeyEnc());
            String secretKey = aesUtil.decrypt(config.getSecretKeyEnc());
            Map<String, Object> token = storageTokenIssuer.issueToken(
                    config, accessKey, secretKey);
            String ciphertext = aesUtil.encrypt(objectMapper.writeValueAsString(token));
            // This legacy CBC payload remains behind the disabled ANCHR-104 feature gate.
            // ANCHR-110 owns replacing it with an authenticated envelope before re-enabling
            // embedded-image upload.
            byte[] iv = new byte[16];
            SECURE_RANDOM.nextBytes(iv);
            return Map.of(
                    "iv", Base64.getEncoder().encodeToString(iv),
                    "ciphertext", ciphertext);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "Failed to issue temporary Docling output credentials.", e);
        }
    }

    private String encodeSnapshot(IngestionParseRequestSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot.validated());
        } catch (JsonProcessingException e) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR, "Failed to persist Docling request parameters.", e);
        }
    }

    private IngestionParseRequestSnapshot decodeSnapshot(String snapshotJson) {
        if (!StringUtils.hasText(snapshotJson)) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR, "Docling request parameters are missing.");
        }
        try {
            return objectMapper.readValue(
                    snapshotJson, IngestionParseRequestSnapshot.class).validated();
        } catch (JsonProcessingException | IllegalStateException e) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR, "Persisted Docling request parameters are invalid.", e);
        }
    }

    private void handleClaimFailure(IngestionTaskItem item,
                                    Asset asset,
                                    RuntimeException exception) {
        if (exception instanceof StaleClaimException stale) {
            throw stale;
        }
        if (exception instanceof AmbiguousContextWriteException) {
            log.warn("parse context write outcome is ambiguous; leaving claim for lease recovery, itemId={}, cause={}",
                    item.getId(), exception.getCause() == null
                            ? exception.getMessage() : exception.getCause().getMessage());
            return;
        }
        if (exception instanceof WorkerInterruptedException) {
            log.info("ingestion worker interrupted; leaving claim for lease recovery, itemId={}, stage={}",
                    item.getId(), item.getExecutionStage());
            return;
        }
        if (exception instanceof DoclingClientException doclingFailure) {
            handleDoclingClientFailure(item, asset, doclingFailure);
            return;
        }
        if (exception instanceof IngestionArtifactException artifactFailure) {
            if (artifactFailure.isRetryable()) {
                retryOrFail(item, asset, ApiError.INTERNAL_ERROR,
                        artifactFailure.getMessage(), effectiveStageMaxRetries(), null);
            } else {
                failClaim(item, asset, ApiError.INTERNAL_ERROR, artifactFailure.getMessage());
            }
            return;
        }
        if (exception instanceof BusinessException businessFailure) {
            failClaim(item, asset, businessFailure.getError(), businessFailure.getMessage());
            return;
        }
        if (item.getExecutionStage() == IngestionExecutionStage.EMBED
                && isRateLimitError(exception)) {
            retryOrFail(item, asset, ApiError.EMBEDDING_FAILED,
                    exception.getMessage(), effectiveEmbeddingMaxRetries(),
                    Duration.ofMillis(resolveEmbeddingBackoffMs(
                            item.getStageRetryCount() + 1)));
            return;
        }
        retryOrFail(item, asset, ApiError.INTERNAL_ERROR,
                exception.getMessage(), effectiveStageMaxRetries(), null);
    }

    private void handleDoclingClientFailure(IngestionTaskItem item,
                                            Asset asset,
                                            DoclingClientException exception) {
        switch (exception.kind()) {
            case TRANSIENT -> retryOrFail(
                    item,
                    asset,
                    ApiError.TEXT_PARSE_FAILED,
                    exception.getMessage(),
                    effectiveStageMaxRetries(),
                    exception.retryAfter());
            case NOT_FOUND -> moveBackToParseSubmit(
                    item, asset, "Docling job no longer exists.");
            case CONFLICT, CONFIGURATION, PERMANENT -> failClaim(
                    item, asset, ApiError.TEXT_PARSE_FAILED, exception.getMessage());
        }
    }

    private void handleFailedDoclingJob(IngestionTaskItem item,
                                        Asset asset,
                                        DoclingJobError error) {
        String code = error == null || !StringUtils.hasText(error.code())
                ? "UNKNOWN" : error.code().trim().toUpperCase(Locale.ROOT);
        String message = error == null
                ? "Docling job failed."
                : "Docling job failed [" + code + "]: " + clip(error.message(), 300);
        boolean retryable = "QUEUE_TIMEOUT".equals(code)
                || "INTERNAL_ERROR".equals(code)
                || "SOURCE_DOWNLOAD_ERROR".equals(code);
        if (!retryable) {
            failClaim(item, asset, ApiError.TEXT_PARSE_FAILED, message);
            return;
        }
        if (item.getStageRetryCount() + 1 > effectiveStageMaxRetries()) {
            failClaim(item, asset, ApiError.TEXT_PARSE_FAILED, message);
            return;
        }
        try {
            // A failed record must be removed before submitting the same v2 identity, otherwise
            // Docling correctly returns the same terminal job forever.
            doclingClient.ackJob(item.getDoclingJobId());
        } catch (DoclingClientException e) {
            handleDoclingClientFailure(item, asset, e);
            return;
        }
        moveBackToParseSubmit(item, asset, message);
    }

    private void moveBackToParseSubmit(IngestionTaskItem item,
                                       Asset asset,
                                       String reason) {
        int retryCount = item.getStageRetryCount() + 1;
        if (retryCount > effectiveStageMaxRetries()) {
            failClaim(item, asset, ApiError.TEXT_PARSE_FAILED, reason);
            return;
        }
        IngestionTaskItem withoutJob = item.toBuilder().doclingJobId(null).build();
        transitionOrStop(runningTransition(
                withoutJob,
                IngestionExecutionStage.PARSE_SUBMIT,
                LocalDateTime.now().plus(retryDelay(retryCount)),
                retryCount,
                IngestionStage.PARSE,
                STAGE_PARSE_PROGRESS));
    }

    private void retryOrFail(IngestionTaskItem item,
                             Asset asset,
                             ApiError terminalError,
                             String message,
                             int maxRetries,
                             Duration requestedDelay) {
        int retryCount = item.getStageRetryCount() + 1;
        if (retryCount > Math.max(0, maxRetries)) {
            failClaim(item, asset, terminalError, message);
            return;
        }
        Duration delay = positiveDuration(requestedDelay)
                ? requestedDelay : retryDelay(retryCount);
        transitionOrStop(runningTransition(
                item,
                item.getExecutionStage(),
                LocalDateTime.now().plus(delay),
                retryCount,
                publicStage(item.getExecutionStage()),
                publicProgress(item.getExecutionStage())));
    }

    private void failClaim(IngestionTaskItem item,
                           Asset asset,
                           ApiError error,
                           String message) {
        if (item == null || item.getExecutionStage() == null
                || item.getExecutionStage().isTerminal()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String safeMessage = clip(
                StringUtils.hasText(message) ? message : error.getMessage(),
                ERROR_MESSAGE_MAX_LENGTH);
        IngestionClaimTransition transition = IngestionClaimTransitions.copyOf(item, now)
                .nextExecutionStage(IngestionExecutionStage.FAILED)
                .nextStageAttempt(0)
                .nextStageRetryCount(item.getStageRetryCount())
                .nextStageStartedAt(now)
                .nextActionAt(null)
                .stage(publicStage(item.getExecutionStage()))
                .status(IngestionTaskItemStatus.FAILED)
                .progress(Math.max(item.getProgress(), publicProgress(item.getExecutionStage())))
                .errorCode(error.name())
                .errorMessage(safeMessage)
                .finishedAt(now)
                .build();
        boolean transitioned = transactionCoordinator.transitionFailed(
                transition,
                asset,
                DocumentParseStatus.FAILED.name(),
                DocumentIndexStatus.FAILED.name(),
                asset == null ? 0 : asset.getSegmentCount(),
                asset == null ? 0 : asset.getIndexedSegmentCount());
        if (transitioned) {
            log.warn("knowledge-base ingestion item failed, taskId={}, itemId={}, stage={}, errorCode={}, error={}",
                    item.getTaskId(), item.getId(), item.getExecutionStage(), error, safeMessage);
        }
    }

    private IngestionClaimTransition runningTransition(IngestionTaskItem item,
                                                       IngestionExecutionStage nextStage,
                                                       LocalDateTime nextActionAt,
                                                       int nextRetryCount,
                                                       IngestionStage publicStage,
                                                       int progress) {
        LocalDateTime now = LocalDateTime.now();
        boolean sameStage = item.getExecutionStage() == nextStage;
        return IngestionClaimTransitions.copyOf(item, now)
                .nextExecutionStage(nextStage)
                .nextStageAttempt(sameStage ? item.getStageAttempt() : 0)
                .nextStageRetryCount(Math.max(0, nextRetryCount))
                .nextStageStartedAt(sameStage && item.getStageStartedAt() != null
                        ? item.getStageStartedAt() : now)
                .nextActionAt(nextActionAt)
                .stage(publicStage)
                .status(IngestionTaskItemStatus.RUNNING)
                .progress(Math.max(item.getProgress(), progress))
                .errorCode(null)
                .errorMessage(null)
                .finishedAt(null)
                .build();
    }

    private void transitionOrStop(IngestionClaimTransition transition) {
        if (!ingestionTaskRepository.transitionClaim(transition)) {
            throw new StaleClaimException();
        }
    }

    private void assertCurrentClaim(IngestionTaskItem item) {
        if (!ingestionTaskRepository.renewClaim(
                item.getId(),
                item.getExecutionEpoch(),
                item.getExecutionStage(),
                item.getStageAttempt(),
                item.getLeaseToken(),
                effectiveLeaseSeconds())) {
            throw new StaleClaimException();
        }
    }

    private void requireParseJob(IngestionTaskItem item) {
        if (!StringUtils.hasText(item.getDoclingRequestId())
                || !StringUtils.hasText(item.getDoclingJobId())) {
            throw new BusinessException(
                    ApiError.TEXT_PARSE_FAILED, "Persisted Docling job identity is incomplete.");
        }
    }

    private Asset findAsset(IngestionTaskItem item) {
        if (item == null || !StringUtils.hasText(item.getAssetId())) {
            throw new BusinessException(
                    ApiError.DOCUMENT_NOT_FOUND, "Task item is not linked to a document asset.");
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
            return assetRepository.findActiveById(item.getKbId(), item.getAssetId())
                    .orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String resolveSourceUrl(Asset asset, IngestionTaskItem item) {
        if (StringUtils.hasText(asset.getObjectKey())) {
            return objectStoragePort.buildDownloadUrl(asset.getObjectKey());
        }
        if (StringUtils.hasText(asset.getSourceUrl())) {
            return asset.getSourceUrl().trim();
        }
        if (StringUtils.hasText(item.getSourceUrl())) {
            return item.getSourceUrl().trim();
        }
        throw new BusinessException(
                ApiError.TEXT_PARSE_FAILED, "Document has no parseable source location.");
    }

    private List<Segment> buildSegments(Asset asset, List<Chunk> chunks) {
        return chunks.stream()
                .filter(Objects::nonNull)
                .filter(chunk -> StringUtils.hasText(chunk.getSegmentId()))
                .map(chunk -> Segment.builder()
                        .segmentId(chunk.getSegmentId())
                        .kbId(chunk.getKbId())
                        .assetId(asset.getId())
                        .assetType(asset.getFileType())
                        .segmentType(isImage(asset)
                                ? SegmentType.IMAGE_OCR_BLOCK : SegmentType.TEXT_CHUNK)
                        .title(chunk.getTitle())
                        .contentText(chunk.getChunkText())
                        .ocrText(chunk.getOcrText())
                        .embedding(chunk.getEmbedding())
                        .pageNo(chunk.getPageNo())
                        .chunkOrder(chunk.getChunkOrder())
                        .sourceRef(chunk.getSourceRef())
                        .createdAt(System.currentTimeMillis())
                        .bbox(chunk.getBboxInfos())
                        .build())
                .toList();
    }

    private void enrichTextEmbeddings(IngestionTaskItem item,
                                      Asset asset,
                                      List<Chunk> chunks,
                                      String imageInput) {
        Chunk firstUsableChunk = findFirstUsableChunk(chunks);
        if (firstUsableChunk == null) {
            throw new BusinessException(
                    ApiError.TEXT_PARSE_FAILED, "Docling returned no usable chunks.");
        }
        boolean multi = embeddingPort.isMulti();
        boolean image = isImage(asset);
        if (image && multi) {
            List<Float> imageEmbedding = embed(item, imageInput, "image");
            if (imageEmbedding == null || imageEmbedding.isEmpty()) {
                throw new BusinessException(ApiError.EMBEDDING_RESULT_EMPTY);
            }
            firstUsableChunk.setEmbedding(imageEmbedding);
            return;
        }

        for (Chunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            String text = image ? chunk.getOcrText() : chunk.getChunkText();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            List<Float> embedding = embed(item, text, "text");
            if (embedding == null || embedding.isEmpty()) {
                throw new BusinessException(ApiError.EMBEDDING_RESULT_EMPTY);
            }
            chunk.setEmbedding(embedding);
        }
    }

    private List<Float> embed(IngestionTaskItem item, String input, String inputType) {
        assertCurrentClaim(item);
        reserveEmbeddingCallSlot();
        assertCurrentClaim(item);
        return embeddingPort.embed(input, inputType);
    }

    private void reserveEmbeddingCallSlot() {
        long waitMs;
        synchronized (embeddingPaceLock) {
            long now = System.currentTimeMillis();
            waitMs = Math.max(0L, nextEmbeddingCallAt - now);
            nextEmbeddingCallAt = Math.max(now, nextEmbeddingCallAt)
                    + Math.max(0L, embeddingMinIntervalMs);
        }
        if (waitMs <= 0L) {
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerInterruptedException(e);
        }
    }

    private Chunk findFirstUsableChunk(List<Chunk> chunks) {
        if (chunks == null) {
            return null;
        }
        for (Chunk chunk : chunks) {
            if (chunk != null) {
                return chunk;
            }
        }
        return null;
    }

    private void cleanupOverwrittenAsset(String kbId,
                                         IngestionTaskItem item,
                                         String userId) {
        if (item.getDedupeResult() != DedupeResult.OVERWRITTEN
                || !StringUtils.hasText(item.getDuplicateAssetId())
                || item.getDuplicateAssetId().equals(item.getAssetId())) {
            return;
        }
        String oldAssetId = item.getDuplicateAssetId().trim();
        try {
            boolean deleted = assetRepository.markDeleted(
                    kbId, oldAssetId, userId, LocalDateTime.now());
            if (!deleted) {
                log.warn("overwritten asset cleanup skipped, old asset not found, kbId={}, oldAssetId={}",
                        kbId, oldAssetId);
                return;
            }
            segmentRepository.deleteByAssetId(oldAssetId);
        } catch (RuntimeException e) {
            // Reliable overwrite cleanup and its outbox belong to ANCHR-107. Preserve the
            // existing best-effort behavior here without rolling back a completed item.
            log.warn("overwritten asset cleanup failed, kbId={}, oldAssetId={}: {}",
                    kbId, oldAssetId, e.getMessage());
        }
    }

    private void acknowledgeBestEffort(String jobId) {
        if (!StringUtils.hasText(jobId)) {
            return;
        }
        try {
            doclingClient.ackJob(jobId);
        } catch (RuntimeException e) {
            // The durable artifact reference is already committed. Docling TTL cleanup is the
            // fallback, and ACK failure must not move the item back to PARSE.
            log.warn("Docling result ACK failed after durable persistence, jobId={}: {}",
                    jobId, e.getMessage());
        }
    }

    private boolean parseStageExpired(IngestionTaskItem item) {
        return item.getStageStartedAt() != null
                && LocalDateTime.now().isAfter(
                item.getStageStartedAt().plus(effectiveParseStageTimeout()));
    }

    private IngestionStage publicStage(IngestionExecutionStage stage) {
        return switch (stage) {
            case PARSE_SUBMIT, PARSE_WAIT, PARSE_PERSIST -> IngestionStage.PARSE;
            case EMBED -> IngestionStage.EMBED;
            case INDEX -> IngestionStage.INDEX;
            case COMPLETE -> IngestionStage.ASKABLE;
            case FAILED -> IngestionStage.PARSE;
        };
    }

    private int publicProgress(IngestionExecutionStage stage) {
        return switch (stage) {
            case PARSE_SUBMIT, PARSE_WAIT, PARSE_PERSIST, FAILED -> STAGE_PARSE_PROGRESS;
            case EMBED -> STAGE_EMBED_PROGRESS;
            case INDEX -> STAGE_INDEX_PROGRESS;
            case COMPLETE -> 100;
        };
    }

    private boolean isImage(Asset asset) {
        return "IMAGE".equalsIgnoreCase(asset.getFileType());
    }

    private boolean isRateLimitError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AiClient.OpenAiException openAiFailure
                    && openAiFailure.statusCode() == 429) {
                return true;
            }
            String message = current.getMessage();
            if (StringUtils.hasText(message)) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (message.contains("429")
                        || message.contains("Throttling")
                        || message.contains("RateQuota")
                        || lower.contains("rate limit")) {
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
        long seconds = Math.min(300L, 1L << Math.min(Math.max(0, retryCount - 1), 8));
        return Duration.ofSeconds(seconds);
    }

    private boolean positiveDuration(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private int effectiveBatchSize() {
        return Math.max(1, claimBatchSize);
    }

    private long effectiveLeaseSeconds() {
        return Math.max(30L, claimLeaseSeconds);
    }

    private int effectiveStageMaxRetries() {
        return Math.max(1, stageMaxRetries);
    }

    private int effectiveEmbeddingMaxRetries() {
        // The configuration is intentionally expressed as total provider-call attempts.
        // The first call happens before any persisted retry, so N attempts allow N - 1
        // retry transitions.
        return Math.max(0, embeddingRateLimitMaxAttempts - 1);
    }

    private Duration effectiveParsePollInterval() {
        return positiveDuration(parsePollInterval)
                ? parsePollInterval : DEFAULT_PARSE_POLL_INTERVAL;
    }

    private Duration effectiveParseStageTimeout() {
        return positiveDuration(parseStageTimeout)
                ? parseStageTimeout : DEFAULT_PARSE_STAGE_TIMEOUT;
    }

    private String updatedBy(IngestionTaskItem item) {
        return StringUtils.hasText(item.getTaskCreatedBy())
                ? item.getTaskCreatedBy() : "system";
    }

    private String clip(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static final class StaleClaimException extends RuntimeException {
    }

    private static final class AmbiguousContextWriteException extends RuntimeException {
        private AmbiguousContextWriteException(Throwable cause) {
            super(cause);
        }
    }

    private static final class WorkerInterruptedException extends RuntimeException {
        private WorkerInterruptedException(Throwable cause) {
            super(cause);
        }
    }
}
