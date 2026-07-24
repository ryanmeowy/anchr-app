package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.ingestion.application.IngestionTaskProcessor;
import com.anchr.core.ingestion.application.artifact.IngestionArtifactException;
import com.anchr.core.ingestion.application.artifact.IngestionArtifactStore;
import com.anchr.core.ingestion.application.artifact.IngestionStoredArtifact;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.ingestion.domain.model.IngestionClaimContext;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjection;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjectionPolicy;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
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
import com.anchr.core.search.domain.model.EmbeddingProjection;
import com.anchr.core.search.domain.model.EmbeddingProjectionPolicy;
import com.anchr.core.search.domain.model.EmbeddingProjectionPolicy.Profile;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentIdentity;
import com.anchr.core.search.domain.model.SegmentType;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * <p>Each worker owns one fenced database claim. The EMBED-to-INDEX handoff retains that
 * claim so freshly generated vectors can be indexed from memory. If the process exits after
 * the handoff, the recovered INDEX claim regenerates vectors from the durable parse artifact.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionTaskProcessorImpl implements IngestionTaskProcessor {

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
        IngestionTaskItem failureContext = item;
        try {
            item = transactionCoordinator.ensureTargetIndexGeneration(item);
            failureContext = item;
            if (item.getStageRetryCount() > effectiveStageMaxRetries()) {
                failClaim(item, tryFindAsset(item), ApiError.INTERNAL_ERROR,
                        "Ingestion stage exceeded its recovery-attempt limit.");
                return;
            }
            switch (item.getExecutionStage()) {
                case PARSE_SUBMIT -> processParseSubmit(item);
                case PARSE_WAIT -> processParseWait(item);
                case PARSE_PERSIST -> processParsePersist(item);
                case EMBED -> {
                    PreparedIndex prepared = processEmbed(item);
                    failureContext = prepared.item();
                    processIndex(prepared.item(), prepared.asset(), prepared.segments());
                }
                case INDEX -> processIndex(item);
                case COMPLETE, FAILED -> log.debug(
                        "terminal ingestion item was unexpectedly claimed, itemId={}, stage={}",
                        item.getId(), item.getExecutionStage());
            }
        } catch (StaleClaimException stale) {
            log.debug("stale ingestion worker stopped, itemId={}, stage={}, attempt={}",
                    item.getId(), item.getExecutionStage(), item.getClaimVersion());
        } catch (RuntimeException e) {
            handleClaimFailure(failureContext, tryFindAsset(failureContext), e);
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
                        item.getStageRetryCount()));
                case "succeeded" -> transitionOrStop(runningTransition(
                        withJob,
                        IngestionExecutionStage.PARSE_PERSIST,
                        LocalDateTime.now(),
                        item.getStageRetryCount()));
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
                    item.getStageRetryCount()));
            case "succeeded" -> transitionOrStop(runningTransition(
                    item,
                    IngestionExecutionStage.PARSE_PERSIST,
                    LocalDateTime.now(),
                    item.getStageRetryCount()));
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
                    0));
            acknowledgeAfterCommit(item.getDoclingJobId());
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
                    item.getStageRetryCount()));
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
        IngestionStoredArtifact artifact =
                artifactStore.writeParseArtifact(item, job.jobId(), result);
        IngestionTaskItem withArtifact = item.toBuilder()
                .parseResultObjectKey(artifact.objectKey())
                .build();
        IngestionClaimTransition transition = runningTransition(
                        withArtifact,
                        IngestionExecutionStage.EMBED,
                        LocalDateTime.now(),
                        0)
                .toBuilder()
                .parseResultSha256(artifact.sha256())
                .build();
        boolean transitioned = ingestionTaskRepository.transitionClaim(transition);
        if (!transitioned) {
            // The immutable object may become an orphan, but a stale worker must not ACK the
            // winner's only recoverable Docling result.
            throw new StaleClaimException();
        }
        acknowledgeAfterCommit(job.jobId());
    }

    private PreparedIndex processEmbed(IngestionTaskItem item) {
        Asset asset = findAsset(item);
        List<Segment> segments = prepareSegments(item, asset);
        LocalDateTime now = LocalDateTime.now();
        IngestionClaimTransition transition = runningTransition(
                        item,
                        IngestionExecutionStage.INDEX,
                        now,
                        0)
                .toBuilder()
                .retainLease(true)
                .build();
        boolean transitioned = transactionCoordinator.transitionAndUpdateAssetStatus(
                transition,
                asset,
                DocumentParseStatus.SUCCESS.name(),
                DocumentIndexStatus.RUNNING.name());
        if (!transitioned) {
            throw new StaleClaimException();
        }
        IngestionTaskItem indexClaim = item.toBuilder()
                .executionStage(IngestionExecutionStage.INDEX)
                .stageRetryCount(0)
                .stageStartedAt(transition.getNextStageStartedAt())
                .nextActionAt(transition.getNextActionAt())
                .stage(transition.getStage())
                .status(transition.getStatus())
                .progress(transition.getProgress())
                .errorCode(null)
                .errorMessage(null)
                .finishedAt(null)
                .build();
        return new PreparedIndex(indexClaim, asset, segments);
    }

    private List<Segment> prepareSegments(IngestionTaskItem item, Asset asset) {
        ParseResponse parsed = artifactStore.readParseResult(item);
        if (parsed.chunks() == null || parsed.chunks().isEmpty()) {
            throw new BusinessException(
                    ApiError.TEXT_PARSE_FAILED, "Docling returned empty chunks.");
        }
        long targetIndexGeneration = requireTargetIndexGeneration(item);
        List<Chunk> chunks = doclingChunkMapper.toTextChunks(
                asset, parsed, targetIndexGeneration);
        if (chunks == null || chunks.isEmpty()) {
            throw new BusinessException(
                    ApiError.TEXT_PARSE_FAILED, "Docling returned no usable chunks.");
        }

        Profile profile = Profile.fromMulti(embeddingPort.isMulti());
        List<Segment> segments = buildSegments(
                item, asset, chunks, targetIndexGeneration, profile);
        String imageInput = EmbeddingProjectionPolicy.requiresImageVisual(
                profile, asset.getFileType())
                ? resolveSourceUrl(asset, item)
                : null;
        segments = applyEmbeddings(item, asset, segments, profile, imageInput);
        assertCurrentClaim(item);
        return segments;
    }

    private void processIndex(IngestionTaskItem item) {
        Asset asset = findAsset(item);
        List<Segment> segments = prepareSegments(item, asset);
        processIndex(item, asset, segments);
    }

    private void processIndex(
            IngestionTaskItem item, Asset asset, List<Segment> segments) {
        boolean indexed = ingestionIndexFinalizer.finalizeIndex(
                item, asset, segments);
        if (!indexed) {
            return;
        }
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
                            .claimVersion(item.getClaimVersion())
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
        if (exception instanceof EmbeddingCallException embeddingFailure) {
            if (isRateLimitError(embeddingFailure)) {
                retryOrFail(item, asset, ApiError.EMBEDDING_FAILED,
                        embeddingFailure.getMessage(), effectiveEmbeddingMaxRetries(),
                        Duration.ofMillis(resolveEmbeddingBackoffMs(
                                item.getStageRetryCount() + 1)));
            } else {
                retryOrFail(item, asset, ApiError.INTERNAL_ERROR,
                        embeddingFailure.getMessage(), effectiveStageMaxRetries(), null);
            }
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
                retryCount));
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
                retryCount));
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
        IngestionPublicProjection projection =
                IngestionPublicProjectionPolicy.failed(
                        item.getExecutionStage(), item.getProgress());
        IngestionClaimTransition transition = IngestionClaimTransitions.copyOf(item, now)
                .nextExecutionStage(IngestionExecutionStage.FAILED)
                .nextStageRetryCount(item.getStageRetryCount())
                .nextStageStartedAt(now)
                .nextActionAt(null)
                .stage(projection.stage())
                .status(projection.status())
                .progress(projection.progress())
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
                                                       int nextRetryCount) {
        LocalDateTime now = LocalDateTime.now();
        boolean sameStage = item.getExecutionStage() == nextStage;
        IngestionPublicProjection projection =
                IngestionPublicProjectionPolicy.running(nextStage, item.getProgress());
        return IngestionClaimTransitions.copyOf(item, now)
                .nextExecutionStage(nextStage)
                .nextStageRetryCount(Math.max(0, nextRetryCount))
                .nextStageStartedAt(sameStage && item.getStageStartedAt() != null
                        ? item.getStageStartedAt() : now)
                .nextActionAt(nextActionAt)
                .stage(projection.stage())
                .status(projection.status())
                .progress(projection.progress())
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
                item.getClaimVersion(),
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

    private List<Segment> buildSegments(
            IngestionTaskItem item,
            Asset asset,
            List<Chunk> chunks,
            long targetIndexGeneration,
            Profile profile
    ) {
        long createdAt = System.currentTimeMillis();
        List<Segment> segments = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (chunk == null || !StringUtils.hasText(chunk.getSegmentId())) {
                continue;
            }
            segments.add(Segment.builder()
                    .segmentId(chunk.getSegmentId())
                    .kbId(chunk.getKbId())
                    .assetId(asset.getId())
                    .indexGeneration(targetIndexGeneration)
                    .assetType(asset.getFileType())
                    .segmentType(isImage(asset)
                            ? SegmentType.IMAGE_OCR_BLOCK : SegmentType.TEXT_CHUNK)
                    .title(chunk.getTitle())
                    .contentText(chunk.getChunkText())
                    .ocrText(chunk.getOcrText())
                    .pageNo(chunk.getPageNo())
                    .chunkOrder(chunk.getChunkOrder())
                    .sourceRef(chunk.getSourceRef())
                    .createdAt(createdAt)
                    .bbox(chunk.getBboxInfos())
                    .build());
        }
        if (EmbeddingProjectionPolicy.requiresImageVisual(
                profile, asset.getFileType())) {
            segments.add(Segment.builder()
                    .segmentId(SegmentIdentity.imageVisual(
                            asset.getId(), targetIndexGeneration))
                    .kbId(asset.getKbId())
                    .assetId(asset.getId())
                    .indexGeneration(targetIndexGeneration)
                    .assetType(asset.getFileType())
                    .segmentType(SegmentType.IMAGE_VISUAL)
                    .title(StringUtils.hasText(asset.getTitle())
                            ? asset.getTitle() : asset.getFileName())
                    .sourceRef(stableSourceRef(asset, item))
                    .thumbnail(asset.getThumbnailKey())
                    .createdAt(createdAt)
                    .build());
        }
        return segments;
    }

    private long requireTargetIndexGeneration(IngestionTaskItem item) {
        if (item.getTargetIndexGeneration() == null
                || item.getTargetIndexGeneration() < 1L) {
            throw new IllegalStateException(
                    "Ingestion item has no valid target index generation.");
        }
        return item.getTargetIndexGeneration();
    }

    private List<Segment> applyEmbeddings(
            IngestionTaskItem item,
            Asset asset,
            List<Segment> segments,
            Profile profile,
            String imageInput
    ) {
        List<Segment> embedded = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            String projectionImageSource =
                    segment.getSegmentType() == SegmentType.IMAGE_VISUAL
                            ? imageInput : null;
            Optional<EmbeddingProjection> projection =
                    EmbeddingProjectionPolicy.select(
                            profile,
                            asset.getFileType(),
                            segment.getSegmentType(),
                            segment.getContentText(),
                            segment.getOcrText(),
                            projectionImageSource);
            if (projection.isEmpty()) {
                embedded.add(segment.toBuilder().embedding(null).build());
                continue;
            }
            EmbeddingProjection selected = projection.get();
            List<Float> embedding = embed(
                    item,
                    selected.source(),
                    selected.inputType().requestValue());
            if (embedding == null || embedding.isEmpty()) {
                throw new BusinessException(ApiError.EMBEDDING_RESULT_EMPTY);
            }
            embedded.add(segment.toBuilder().embedding(embedding).build());
        }
        return embedded;
    }

    private String stableSourceRef(Asset asset, IngestionTaskItem item) {
        if (StringUtils.hasText(asset.getObjectKey())) {
            return asset.getObjectKey().trim();
        }
        if (StringUtils.hasText(asset.getSourceUrl())) {
            return asset.getSourceUrl().trim();
        }
        return StringUtils.hasText(item.getSourceUrl())
                ? item.getSourceUrl().trim() : null;
    }

    private List<Float> embed(IngestionTaskItem item, String input, String inputType) {
        assertCurrentClaim(item);
        reserveEmbeddingCallSlot();
        assertCurrentClaim(item);
        try {
            return embeddingPort.embed(input, inputType);
        } catch (StaleClaimException | WorkerInterruptedException e) {
            throw e;
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EmbeddingCallException(e);
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

    private void acknowledgeAfterCommit(String jobId) {
        if (!StringUtils.hasText(jobId)) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            acknowledgeBestEffort(jobId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        acknowledgeBestEffort(jobId);
                    }
                });
    }

    private boolean parseStageExpired(IngestionTaskItem item) {
        return item.getStageStartedAt() != null
                && LocalDateTime.now().isAfter(
                item.getStageStartedAt().plus(effectiveParseStageTimeout()));
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

    private static final class EmbeddingCallException extends RuntimeException {
        private EmbeddingCallException(Throwable cause) {
            super(cause == null ? null : cause.getMessage(), cause);
        }
    }

    private record PreparedIndex(
            IngestionTaskItem item, Asset asset, List<Segment> segments) {
    }
}
