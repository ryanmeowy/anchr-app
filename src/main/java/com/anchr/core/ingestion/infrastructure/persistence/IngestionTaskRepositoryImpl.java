package com.anchr.core.ingestion.infrastructure.persistence;

import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionArtifactReference;
import com.anchr.core.ingestion.domain.model.IngestionClaimContext;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionKind;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjection;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjectionPolicy;
import com.anchr.core.ingestion.domain.model.IngestionRetryConflictException;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * MyBatis implementation for ingestion task persistence.
 *
 * <p>The public domain model remains API-compatible while persistence is split
 * into stable item, parse-attempt, execution and artifact lifecycles. Only the
 * claimed-execution query assembles the worker's wider runtime view.</p>
 */
@Repository
@RequiredArgsConstructor
public class IngestionTaskRepositoryImpl implements IngestionTaskRepository {

    private static final String SCHEDULER_USER = "ingestion-scheduler";
    private static final String PARSE_ARTIFACT = "PARSE_RESULT";
    private static final String PRODUCED_ARTIFACT = "PRODUCED";
    private static final String LEGACY_ARTIFACT = "LEGACY_BACKFILL";
    private static final int ARTIFACT_VERSION = 1;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<IngestionExecutionStage> PARSE_PHASES = Set.of(
            IngestionExecutionStage.PARSE_SUBMIT,
            IngestionExecutionStage.PARSE_WAIT,
            IngestionExecutionStage.PARSE_PERSIST);

    private final IngestionTaskMapper mapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(IngestionTask task) {
        IngestionExecutionKind initialExecutionKind = Objects.requireNonNull(
                task.getInitialExecutionKind(), "initialExecutionKind");
        DedupeStrategy taskDedupeStrategy = resolveTaskDedupeStrategy(task);
        mapper.insertTask(toRecord(task, taskDedupeStrategy));
        if (task.getItems() == null) {
            return;
        }
        for (IngestionTaskItem item : task.getItems()) {
            mapper.insertItem(toRecord(item));
            if (!requiresExecution(item)) {
                continue;
            }

            IngestionExecutionStage phase = resolveExecutionStage(item);
            IngestionParseAttemptRecord parseAttempt = newParseAttempt(item, phase);
            mapper.insertParseAttempt(parseAttempt);

            IngestionExecutionRecord execution = newExecution(
                    item, parseAttempt.getId(), phase, initialExecutionKind.name());
            mapper.insertExecution(execution);
            registerInitialArtifact(
                    execution.getId(),
                    PARSE_ARTIFACT,
                    item.getParseResultObjectKey(),
                    item.getParseResultArtifact(),
                    execution.getClaimVersion(),
                    item.getUpdatedAt());
            if (mapper.pointItemToExecution(item.getId(), execution.getId(), item.getUpdatedAt()) != 1) {
                throw new IllegalStateException(
                        "Failed to attach the initial ingestion execution to its item.");
            }
        }
    }

    @Override
    public Optional<IngestionTask> findById(String kbId, String taskId) {
        return mapper.findTask(kbId, taskId)
                .map(record -> toDomain(record, mapper.listItems(taskId)));
    }

    @Override
    public Optional<IngestionTask> findByClientRequestId(String createdBy, String clientRequestId) {
        return mapper.findTaskByClientRequestId(createdBy, clientRequestId)
                .map(record -> toDomain(record, mapper.listItems(record.getId())));
    }

    @Override
    public List<IngestionTask> list(String kbId, IngestionTaskStatus status, int limit) {
        String statusValue = status == null ? null : status.name();
        return mapper.listTasks(kbId, statusValue, requirePositiveLimit(limit)).stream()
                .map(record -> toDomain(record, mapper.listItems(record.getId())))
                .toList();
    }

    @Override
    public List<IngestionTask> listRecent(int limit) {
        return mapper.listRecentTasks(requirePositiveLimit(limit)).stream()
                .map(record -> toDomain(record, List.of()))
                .toList();
    }

    @Override
    public List<IngestionTaskItem> listItems(String taskId) {
        return mapper.listItems(taskId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<IngestionTaskItem> listFailedItems(String kbId, String taskId) {
        return mapper.listFailedItems(kbId, taskId).stream()
                .map(this::toRetryDomain)
                .toList();
    }

    @Override
    public Optional<IngestionTaskItem> findItem(String kbId, String taskId, String itemId) {
        return mapper.findItem(kbId, taskId, itemId).map(this::toDomain);
    }

    @Override
    public Optional<IngestionTaskItem> findRetryItem(
            String kbId, String taskId, String itemId) {
        return mapper.findRetryItem(kbId, taskId, itemId).map(this::toRetryDomain);
    }

    @Override
    public List<String> listClaimableItemIds(int limit) {
        return mapper.listClaimableItemIds(requirePositiveLimit(limit));
    }

    @Override
    public List<String> listClaimableItemIds(String taskId, int limit) {
        return mapper.listClaimableItemIdsByTask(taskId, requirePositiveLimit(limit));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<IngestionTaskItem> claimOne(String itemId, long leaseSeconds) {
        requirePositiveLease(leaseSeconds);
        Optional<ClaimCandidateRecord> candidate = mapper.selectClaimableItemForUpdate(itemId);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        ClaimCandidateRecord record = candidate.get();
        String leaseToken = UUID.randomUUID().toString();
        if (mapper.claimExecution(record, leaseToken, leaseSeconds) != 1) {
            return Optional.empty();
        }
        IngestionPublicProjection projection =
                IngestionPublicProjectionPolicy.running(
                        IngestionExecutionStage.valueOf(record.getPhase()),
                        defaultInt(record.getItemProgress()));
        if (mapper.projectClaimedItem(
                itemId, record.getExecutionId(), projection) != 1) {
            throw new IllegalStateException(
                    "Claimed execution could not be projected to its current item.");
        }
        boolean includeParseSnapshot = PARSE_PHASES.contains(parsePhase(record.getPhase()));
        ClaimedExecutionRecord claimed = mapper.findClaimedExecution(
                        itemId, leaseToken, includeParseSnapshot)
                .orElseThrow(() -> new IllegalStateException(
                        "Claimed ingestion execution disappeared before commit."));
        mapper.refreshSummary(
                claimed.getKbId(),
                claimed.getTaskId(),
                hasText(claimed.getTaskCreatedBy())
                        ? claimed.getTaskCreatedBy() : SCHEDULER_USER,
                claimed.getClaimUpdatedAt());
        return Optional.of(toDomain(claimed));
    }

    @Override
    public boolean renewClaim(String itemId, long executionEpoch,
                              IngestionExecutionStage expectedExecutionStage,
                              long claimVersion, String leaseToken, long leaseSeconds) {
        requirePositiveLease(leaseSeconds);
        return mapper.renewClaim(
                itemId, executionEpoch, expectedExecutionStage,
                claimVersion, leaseToken, leaseSeconds) == 1;
    }

    @Override
    public boolean updateClaimContext(IngestionClaimContext context) {
        return mapper.updateClaimContext(context) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean transitionClaim(IngestionClaimTransition transition) {
        validatePublicProjection(transition);
        if (mapper.transitionExecution(transition) != 1) {
            return false;
        }
        if (PARSE_PHASES.contains(transition.getExpectedExecutionStage())
                && mapper.updateParseAttemptFromTransition(transition) != 1) {
            throw new IllegalStateException(
                    "The current parse attempt disappeared during a fenced transition.");
        }
        if (mapper.projectTransitionToItem(transition) != 1) {
            throw new IllegalStateException(
                    "The transitioned execution could not be projected to its item.");
        }

        Long executionId = mapper.findCurrentExecutionId(
                        transition.getItemId(), transition.getExecutionEpoch())
                .orElseThrow(() -> new IllegalStateException(
                        "The current execution pointer changed during a fenced transition."));
        registerProducedArtifact(
                executionId,
                PARSE_ARTIFACT,
                transition.getParseResultObjectKey(),
                transition.getParseResultSha256(),
                transition.getExpectedClaimVersion(),
                transition.getUpdatedAt());
        mapper.refreshSummary(
                transition.getKbId(),
                transition.getTaskId(),
                hasText(transition.getUpdatedBy())
                        ? transition.getUpdatedBy() : SCHEDULER_USER,
                transition.getUpdatedAt() == null
                        ? LocalDateTime.now() : transition.getUpdatedAt());
        return true;
    }

    private void validatePublicProjection(IngestionClaimTransition transition) {
        if (transition.isRetainLease()
                && (transition.getExpectedExecutionStage() != IngestionExecutionStage.EMBED
                || transition.getNextExecutionStage() != IngestionExecutionStage.INDEX)) {
            throw new IllegalArgumentException(
                    "A claim lease may only be retained while handing EMBED directly to INDEX.");
        }
        IngestionPublicProjection expected = IngestionPublicProjectionPolicy.transition(
                transition.getExpectedExecutionStage(),
                transition.getNextExecutionStage(),
                transition.getProgress());
        IngestionPublicProjection supplied = new IngestionPublicProjection(
                transition.getStage(),
                transition.getStatus(),
                transition.getProgress());
        if (!expected.equals(supplied)) {
            throw new IllegalArgumentException(
                    "Ingestion transition carries a public projection inconsistent with its phase.");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isClaimCurrentForUpdate(String itemId, long executionEpoch,
                                           IngestionExecutionStage expectedExecutionStage,
                                           long claimVersion, String leaseToken) {
        return mapper.findCurrentClaimForUpdate(
                itemId, executionEpoch, expectedExecutionStage,
                claimVersion, leaseToken).isPresent();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean resetFailedItem(String kbId, String taskId,
                                   String itemId, int expectedParseAttempt,
                                   int nextParseAttempt, String nextDoclingRequestId,
                                   LocalDateTime updatedAt) {
        FailedItemRetryRecord failed = mapper.selectFailedItemForRetryForUpdate(
                        kbId, taskId, itemId, expectedParseAttempt)
                .orElse(null);
        if (failed == null
                || (failed.getExecutionStatus() != null
                && !"FAILED".equals(failed.getExecutionStatus()))) {
            return false;
        }

        IngestionParseAttemptRecord parseAttempt = new IngestionParseAttemptRecord();
        parseAttempt.setItemId(itemId);
        parseAttempt.setAttemptNo(nextParseAttempt);
        parseAttempt.setRequestId(nextDoclingRequestId);
        parseAttempt.setSourceRevision(failed.getSourceRevision());
        parseAttempt.setStatus("ACTIVE");
        parseAttempt.setCreatedAt(updatedAt);
        parseAttempt.setUpdatedAt(updatedAt);
        mapper.insertParseAttempt(parseAttempt);

        long currentEpoch = failed.getExecutionEpoch() == null ? 1L : failed.getExecutionEpoch();
        long nextEpoch = Math.addExact(currentEpoch, 1L);
        IngestionExecutionRecord execution = new IngestionExecutionRecord();
        execution.setItemId(itemId);
        execution.setParseAttemptId(parseAttempt.getId());
        execution.setExecutionEpoch(nextEpoch);
        execution.setExecutionKind(IngestionExecutionKind.EXPLICIT_RETRY.name());
        execution.setExecutionStatus("ACTIVE");
        execution.setPhase(IngestionExecutionStage.PARSE_SUBMIT.name());
        execution.setClaimVersion(0L);
        execution.setPhaseRetryCount(0);
        execution.setNextActionAt(updatedAt);
        execution.setCreatedAt(updatedAt);
        execution.setUpdatedAt(updatedAt);
        mapper.insertExecution(execution);

        IngestionPublicProjection projection =
                IngestionPublicProjectionPolicy.explicitRetry();
        if (mapper.resetFailedItemPointer(
                kbId, taskId, itemId, failed.getCurrentExecutionId(),
                execution.getId(), projection, updatedAt) != 1) {
            throw new IngestionRetryConflictException(
                    "Failed item changed while its retry execution was being attached.");
        }
        return true;
    }

    @Override
    public void refreshSummary(String kbId, String taskId,
                               String updatedBy, LocalDateTime updatedAt) {
        mapper.refreshSummary(kbId, taskId, updatedBy, updatedAt);
    }

    private IngestionTaskRecord toRecord(IngestionTask task, DedupeStrategy dedupeStrategy) {
        IngestionTaskRecord record = new IngestionTaskRecord();
        record.setId(task.getId());
        record.setKbId(task.getKbId());
        record.setSourceType(task.getSourceType().name());
        record.setClientRequestId(task.getClientRequestId());
        record.setRequestHash(task.getRequestHash());
        record.setDedupeStrategy(dedupeStrategy == null ? null : dedupeStrategy.name());
        record.setStatus(task.getStatus().name());
        record.setTotalCount(task.getTotalCount());
        record.setSuccessCount(task.getSuccessCount());
        record.setFailureCount(task.getFailureCount());
        record.setRunningCount(task.getRunningCount());
        record.setCreatedBy(task.getCreatedBy());
        record.setUpdatedBy(task.getUpdatedBy());
        record.setCreatedAt(task.getCreatedAt());
        record.setUpdatedAt(task.getUpdatedAt());
        record.setFinishedAt(task.getFinishedAt());
        return record;
    }

    private IngestionTaskItemRecord toRecord(IngestionTaskItem item) {
        IngestionTaskItemRecord record = new IngestionTaskItemRecord();
        record.setId(item.getId());
        record.setTaskId(item.getTaskId());
        record.setKbId(item.getKbId());
        record.setAssetId(item.getAssetId());
        record.setFileName(item.getFileName());
        record.setFileHash(item.getFileHash());
        record.setSourceUrl(item.getSourceUrl());
        record.setStage(item.getStage().name());
        record.setStatus(item.getStatus().name());
        record.setProgress(item.getProgress());
        record.setDedupeStrategy(
                item.getDedupeStrategy() == null ? null : item.getDedupeStrategy().name());
        record.setDedupeResult(
                item.getDedupeResult() == null ? null : item.getDedupeResult().name());
        record.setDuplicateAssetId(item.getDuplicateAssetId());
        record.setErrorCode(item.getErrorCode());
        record.setErrorMessage(item.getErrorMessage());
        record.setCreatedAt(item.getCreatedAt());
        record.setUpdatedAt(item.getUpdatedAt());
        record.setFinishedAt(item.getFinishedAt());
        return record;
    }

    private IngestionParseAttemptRecord newParseAttempt(
            IngestionTaskItem item, IngestionExecutionStage phase) {
        IngestionParseAttemptRecord record = new IngestionParseAttemptRecord();
        record.setItemId(item.getId());
        record.setAttemptNo(Math.max(1, item.getParseAttempt()));
        record.setRequestId(item.getDoclingRequestId());
        record.setJobId(item.getDoclingJobId());
        record.setSourceRevision(item.getSourceRevision());
        record.setRequestSnapshot(item.getParseRequestSnapshot());
        boolean parsed = phase == IngestionExecutionStage.EMBED
                || phase == IngestionExecutionStage.INDEX
                || hasText(item.getParseResultObjectKey());
        record.setStatus(parsed ? "SUCCEEDED" : "ACTIVE");
        record.setCreatedAt(item.getCreatedAt());
        record.setUpdatedAt(item.getUpdatedAt());
        record.setFinishedAt(parsed ? item.getUpdatedAt() : null);
        return record;
    }

    private IngestionExecutionRecord newExecution(
            IngestionTaskItem item,
            Long parseAttemptId,
            IngestionExecutionStage phase,
            String executionKind) {
        IngestionExecutionRecord record = new IngestionExecutionRecord();
        record.setItemId(item.getId());
        record.setParseAttemptId(parseAttemptId);
        record.setExecutionEpoch(Math.max(1L, item.getExecutionEpoch()));
        record.setExecutionKind(executionKind);
        record.setPhase(phase.name());
        record.setExecutionStatus("ACTIVE");
        record.setClaimVersion(Math.max(0L, item.getClaimVersion()));
        record.setPhaseRetryCount(Math.max(0, item.getStageRetryCount()));
        record.setPhaseStartedAt(item.getStageStartedAt());
        record.setNextActionAt(resolveNextActionAt(item));
        record.setLeaseToken(item.getLeaseToken());
        record.setLeaseUntil(item.getLeaseUntil());
        record.setErrorCode(item.getErrorCode());
        record.setErrorMessage(item.getErrorMessage());
        record.setCreatedAt(item.getCreatedAt());
        record.setUpdatedAt(item.getUpdatedAt());
        return record;
    }

    private IngestionTask toDomain(
            IngestionTaskRecord record, List<IngestionItemViewRecord> itemRecords) {
        return IngestionTask.builder()
                .id(record.getId())
                .kbId(record.getKbId())
                .sourceType(IngestionSourceType.valueOf(record.getSourceType()))
                .clientRequestId(record.getClientRequestId())
                .requestHash(record.getRequestHash())
                .status(IngestionTaskStatus.valueOf(record.getStatus()))
                .totalCount(defaultInt(record.getTotalCount()))
                .successCount(defaultInt(record.getSuccessCount()))
                .failureCount(defaultInt(record.getFailureCount()))
                .runningCount(defaultInt(record.getRunningCount()))
                .createdBy(record.getCreatedBy())
                .updatedBy(record.getUpdatedBy())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .finishedAt(record.getFinishedAt())
                .items(itemRecords == null
                        ? List.of() : itemRecords.stream().map(this::toDomain).toList())
                .build();
    }

    private IngestionTaskItem toDomain(IngestionItemViewRecord record) {
        return IngestionTaskItem.builder()
                .id(record.getId())
                .taskId(record.getTaskId())
                .kbId(record.getKbId())
                .assetId(record.getAssetId())
                .fileName(record.getFileName())
                .fileHash(record.getFileHash())
                .sourceUrl(record.getSourceUrl())
                .stage(IngestionStage.valueOf(record.getStage()))
                .status(IngestionTaskItemStatus.valueOf(record.getStatus()))
                .progress(defaultInt(record.getProgress()))
                .dedupeStrategy(parseDedupeStrategy(record.getDedupeStrategy()))
                .dedupeResult(parseDedupeResult(record.getDedupeResult()))
                .duplicateAssetId(record.getDuplicateAssetId())
                .errorCode(record.getErrorCode())
                .errorMessage(record.getErrorMessage())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .finishedAt(record.getFinishedAt())
                .build();
    }

    private IngestionTaskItem toDomain(ClaimedExecutionRecord record) {
        IngestionExecutionStage phase =
                IngestionExecutionStage.valueOf(record.getPhase());
        IngestionPublicProjection projection =
                IngestionPublicProjectionPolicy.running(
                        phase, defaultInt(record.getItemProgress()));
        return IngestionTaskItem.builder()
                .id(record.getItemId())
                .taskId(record.getTaskId())
                .kbId(record.getKbId())
                .taskCreatedBy(record.getTaskCreatedBy())
                .assetId(record.getAssetId())
                .sourceUrl(record.getSourceUrl())
                .parseAttempt(defaultAttempt(record.getParseAttemptNo()))
                .doclingRequestId(record.getRequestId())
                .doclingJobId(record.getJobId())
                .sourceRevision(record.getSourceRevision())
                .executionStage(phase)
                .executionEpoch(defaultEpoch(record.getExecutionEpoch()))
                .claimVersion(toClaimVersion(record.getClaimVersion()))
                .stageRetryCount(defaultInt(record.getPhaseRetryCount()))
                .stageStartedAt(record.getPhaseStartedAt())
                .nextActionAt(record.getNextActionAt())
                .leaseToken(record.getLeaseToken())
                .leaseUntil(record.getLeaseUntil())
                .parseRequestSnapshot(record.getRequestSnapshot())
                .parseResultObjectKey(record.getParseResultObjectKey())
                .parseResultArtifact(artifactReference(
                        PARSE_ARTIFACT,
                        record.getParseResultObjectKey(),
                        record.getParseResultArtifactVersion(),
                        record.getParseResultArtifactProvenance(),
                        record.getParseResultProducerClaimVersion(),
                        record.getParseResultSha256()))
                .stage(projection.stage())
                .status(projection.status())
                .progress(projection.progress())
                .dedupeResult(parseDedupeResult(record.getDedupeResult()))
                .duplicateAssetId(record.getDuplicateAssetId())
                .build();
    }

    private IngestionArtifactReference artifactReference(
            String artifactType,
            String objectKey,
            Integer artifactVersion,
            String provenance,
            Long producerClaimVersion,
            String contentSha256) {
        if (!hasText(objectKey)) {
            return null;
        }
        return IngestionArtifactReference.builder()
                .artifactType(artifactType)
                .artifactVersion(artifactVersion == null ? 0 : artifactVersion)
                .provenance(provenance)
                .producerClaimVersion(producerClaimVersion)
                .objectKey(objectKey)
                .contentSha256(contentSha256)
                .build();
    }

    private IngestionTaskItem toRetryDomain(FailedItemRetryRecord record) {
        return IngestionTaskItem.builder()
                .id(record.getItemId())
                .taskId(record.getTaskId())
                .kbId(record.getKbId())
                .parseAttempt(defaultAttempt(record.getParseAttemptNo()))
                .sourceRevision(record.getSourceRevision())
                .executionEpoch(defaultEpoch(record.getExecutionEpoch()))
                .executionStage("FAILED".equals(record.getItemStatus())
                        ? IngestionExecutionStage.FAILED : null)
                .status(IngestionTaskItemStatus.valueOf(record.getItemStatus()))
                .updatedAt(record.getItemUpdatedAt())
                .build();
    }

    private void registerInitialArtifact(
            Long executionId,
            String artifactType,
            String objectKey,
            IngestionArtifactReference reference,
            long currentClaimVersion,
            LocalDateTime createdAt) {
        if (!hasText(objectKey) && reference == null) {
            return;
        }
        if (!hasText(objectKey)
                || reference == null
                || !artifactType.equals(reference.getArtifactType())
                || reference.getArtifactVersion() != ARTIFACT_VERSION
                || !PRODUCED_ARTIFACT.equals(reference.getProvenance())
                || !objectKey.equals(reference.getObjectKey())
                || reference.getProducerClaimVersion() == null
                || reference.getProducerClaimVersion() > currentClaimVersion
                || !hasText(reference.getContentSha256())) {
            throw new IllegalArgumentException(
                    "A new ingestion execution requires complete produced artifact metadata.");
        }
        registerProducedArtifact(
                executionId,
                artifactType,
                objectKey,
                reference.getContentSha256(),
                reference.getProducerClaimVersion(),
                createdAt);
    }

    private void registerProducedArtifact(
            Long executionId,
            String artifactType,
            String objectKey,
            String contentSha256,
            long producerClaimVersion,
            LocalDateTime createdAt) {
        if (!hasText(contentSha256)) {
            if (!hasText(objectKey)) {
                return;
            }
            IngestionArtifactRecord registered =
                    mapper.findArtifact(executionId, artifactType)
                            .orElseThrow(() -> new IllegalStateException(
                                    "An ingestion transition referenced an unregistered artifact."));
            if (!Objects.equals(objectKey, registered.getObjectKey())
                    || !Objects.equals(ARTIFACT_VERSION, registered.getArtifactVersion())) {
                throw new IllegalStateException(
                        "An ingestion transition referenced different artifact metadata.");
            }
            return;
        }
        if (!hasText(objectKey)
                || !SHA256.matcher(contentSha256).matches()
                || producerClaimVersion < 1) {
            throw new IllegalArgumentException(
                    "Produced ingestion artifact metadata is incomplete or invalid.");
        }
        IngestionArtifactRecord proposed = new IngestionArtifactRecord();
        proposed.setExecutionId(executionId);
        proposed.setArtifactType(artifactType);
        proposed.setArtifactVersion(ARTIFACT_VERSION);
        proposed.setProducerClaimVersion(producerClaimVersion);
        proposed.setObjectKey(objectKey);
        proposed.setContentSha256(contentSha256);
        proposed.setProvenance(PRODUCED_ARTIFACT);
        proposed.setCreatedAt(createdAt == null ? LocalDateTime.now() : createdAt);
        mapper.insertArtifact(proposed);

        IngestionArtifactRecord stored = mapper.findArtifact(executionId, artifactType)
                .orElseThrow(() -> new IllegalStateException(
                        "Artifact registration disappeared before commit."));
        if (!sameArtifact(proposed, stored)) {
            throw new IllegalStateException(
                    "An immutable ingestion artifact is already registered with different metadata.");
        }
    }

    private boolean sameArtifact(
            IngestionArtifactRecord proposed, IngestionArtifactRecord stored) {
        if (!Objects.equals(proposed.getExecutionId(), stored.getExecutionId())
                || !Objects.equals(proposed.getArtifactType(), stored.getArtifactType())
                || !Objects.equals(proposed.getArtifactVersion(), stored.getArtifactVersion())
                || !Objects.equals(proposed.getObjectKey(), stored.getObjectKey())) {
            return false;
        }
        if (LEGACY_ARTIFACT.equals(stored.getProvenance())) {
            return stored.getContentSha256() == null
                    || Objects.equals(proposed.getContentSha256(), stored.getContentSha256());
        }
        return Objects.equals(PRODUCED_ARTIFACT, stored.getProvenance())
                && Objects.equals(
                proposed.getProducerClaimVersion(), stored.getProducerClaimVersion())
                && Objects.equals(proposed.getContentSha256(), stored.getContentSha256());
    }

    private DedupeStrategy resolveTaskDedupeStrategy(IngestionTask task) {
        if (task.getItems() == null) {
            return null;
        }
        List<DedupeStrategy> strategies = task.getItems().stream()
                .map(IngestionTaskItem::getDedupeStrategy)
                .distinct()
                .toList();
        if (strategies.size() > 1) {
            throw new IllegalArgumentException(
                    "All ingestion items in one task must use the same dedupe strategy.");
        }
        return strategies.isEmpty() ? null : strategies.get(0);
    }

    private boolean requiresExecution(IngestionTaskItem item) {
        return item.getStatus() == IngestionTaskItemStatus.PENDING
                || item.getStatus() == IngestionTaskItemStatus.RUNNING;
    }

    private IngestionExecutionStage resolveExecutionStage(IngestionTaskItem item) {
        IngestionExecutionStage phase = item.getExecutionStage() == null
                ? IngestionExecutionStage.PARSE_SUBMIT : item.getExecutionStage();
        if (phase.isTerminal()) {
            throw new IllegalArgumentException(
                    "An active ingestion item cannot start at a terminal execution stage.");
        }
        validateExplicitExecutionStage(item, phase);
        return phase;
    }

    private void validateExplicitExecutionStage(
            IngestionTaskItem item, IngestionExecutionStage phase) {
        if (phase == IngestionExecutionStage.EMBED
                && !hasText(item.getParseResultObjectKey())) {
            throw new IllegalArgumentException(
                    "An ingestion item cannot start at EMBED without a parse artifact.");
        }
        if (phase == IngestionExecutionStage.INDEX
                && !hasText(item.getParseResultObjectKey())) {
            throw new IllegalArgumentException(
                    "An ingestion item cannot start at INDEX without a parse artifact.");
        }
    }

    private LocalDateTime resolveNextActionAt(IngestionTaskItem item) {
        if (item.getNextActionAt() != null) {
            return item.getNextActionAt();
        }
        return item.getUpdatedAt() == null ? item.getCreatedAt() : item.getUpdatedAt();
    }

    private IngestionExecutionStage parsePhase(String phase) {
        return hasText(phase) ? IngestionExecutionStage.valueOf(phase) : null;
    }

    private DedupeResult parseDedupeResult(String value) {
        return value == null ? null : DedupeResult.valueOf(value);
    }

    private DedupeStrategy parseDedupeStrategy(String value) {
        return value == null ? null : DedupeStrategy.valueOf(value);
    }

    private int defaultAttempt(Integer value) {
        return value == null ? 1 : Math.max(1, value);
    }

    private long defaultEpoch(Long value) {
        return value == null ? 1L : Math.max(1L, value);
    }

    private long toClaimVersion(Long value) {
        return value == null ? 0L : value;
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private int requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return limit;
    }

    private void requirePositiveLease(long leaseSeconds) {
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("leaseSeconds must be positive");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
