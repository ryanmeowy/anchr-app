package com.anchr.core.ingestion.infrastructure.persistence;

import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionClaimContext;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
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
import java.util.Optional;
import java.util.UUID;

/**
 * MyBatis implementation for ingestion task repository.
 */
@Repository
@RequiredArgsConstructor
public class IngestionTaskRepositoryImpl implements IngestionTaskRepository {

    private static final String SCHEDULER_USER = "ingestion-scheduler";

    private final IngestionTaskMapper mapper;

    @Override
    public void save(IngestionTask task) {
        List<IngestionTaskItemRecord> itemRecords = task.getItems() == null
                ? List.of()
                : task.getItems().stream().map(this::toRecord).toList();
        mapper.insertTask(toRecord(task));
        for (IngestionTaskItemRecord itemRecord : itemRecords) {
            mapper.insertItem(itemRecord);
        }
    }

    @Override
    public Optional<IngestionTask> findById(String kbId, String taskId) {
        Optional<IngestionTaskRecord> task = mapper.findTask( kbId, taskId);
        return task.map(record -> toDomain(record, mapper.listItems(taskId)));
    }

    @Override
    public Optional<IngestionTask> findByClientRequestId(String createdBy, String clientRequestId) {
        Optional<IngestionTaskRecord> task = mapper.findTaskByClientRequestId(createdBy, clientRequestId);
        return task.map(record -> toDomain(record, mapper.listItems(record.getId())));
    }

    @Override
    public List<IngestionTask> list(String kbId, IngestionTaskStatus status, int limit) {
        String statusValue = status == null ? null : status.name();
        return mapper.listTasks( kbId, statusValue, limit).stream()
                .map(record -> toDomain(record, mapper.listItems(record.getId())))
                .toList();
    }

    @Override
    public List<IngestionTask> listRecent(int limit) {
        return mapper.listRecentTasks(limit).stream()
                .map(record -> toDomain(record, List.of()))
                .toList();
    }

    @Override
    public List<IngestionTaskItem> listItems(String taskId) {
        return mapper.listItems(taskId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<IngestionTaskItem> listFailedItems( String kbId, String taskId) {
        return mapper.listFailedItems(kbId, taskId).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<IngestionTaskItem> findItem( String kbId, String taskId, String itemId) {
        return mapper.findItem(kbId, taskId, itemId).map(this::toDomain);
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
        Optional<IngestionTaskItemRecord> candidate = mapper.selectClaimableItemForUpdate(itemId);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        IngestionTaskItemRecord record = candidate.get();
        String leaseToken = UUID.randomUUID().toString();
        if (mapper.claimItem(record, leaseToken, leaseSeconds) != 1) {
            return Optional.empty();
        }
        IngestionTaskItemRecord claimed = mapper.findClaimedItem(itemId, leaseToken)
                .orElseThrow(() -> new IllegalStateException(
                        "Claimed ingestion item disappeared before the transaction completed."));
        mapper.refreshSummary(
                claimed.getKbId(),
                claimed.getTaskId(),
                claimed.getTaskCreatedBy() == null ? SCHEDULER_USER : claimed.getTaskCreatedBy(),
                claimed.getUpdatedAt());
        return Optional.of(toDomain(claimed));
    }

    @Override
    public boolean renewClaim(String itemId, long executionEpoch,
                              IngestionExecutionStage expectedExecutionStage,
                              int stageAttempt, String leaseToken, long leaseSeconds) {
        requirePositiveLease(leaseSeconds);
        return mapper.renewClaim(itemId, executionEpoch, expectedExecutionStage,
                stageAttempt, leaseToken, leaseSeconds) == 1;
    }

    @Override
    public boolean updateClaimContext(IngestionClaimContext context) {
        return mapper.updateClaimContext(context) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean transitionClaim(IngestionClaimTransition transition) {
        if (mapper.transitionClaim(transition) != 1) {
            return false;
        }
        mapper.refreshSummary(
                transition.getKbId(),
                transition.getTaskId(),
                transition.getUpdatedBy() == null ? SCHEDULER_USER : transition.getUpdatedBy(),
                transition.getUpdatedAt() == null ? LocalDateTime.now() : transition.getUpdatedAt());
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isClaimCurrentForUpdate(String itemId, long executionEpoch,
                                           IngestionExecutionStage expectedExecutionStage,
                                           int stageAttempt, String leaseToken) {
        return mapper.findCurrentClaimForUpdate(
                itemId, executionEpoch, expectedExecutionStage, stageAttempt, leaseToken).isPresent();
    }

    @Override
    public boolean resetFailedItem(String kbId, String taskId,
                                   String itemId, int expectedParseAttempt,
                                   int nextParseAttempt, String nextDoclingRequestId,
                                   LocalDateTime updatedAt) {
        return mapper.resetFailedItem(kbId, taskId, itemId, expectedParseAttempt,
                nextParseAttempt, nextDoclingRequestId, updatedAt) > 0;
    }

    @Override
    public void refreshSummary(String kbId, String taskId, String updatedBy, LocalDateTime updatedAt) {
        mapper.refreshSummary(kbId, taskId, updatedBy, updatedAt);
    }

    private IngestionTaskRecord toRecord(IngestionTask task) {
        IngestionTaskRecord record = new IngestionTaskRecord();
        record.setId(task.getId());
        record.setKbId(task.getKbId());
        record.setSourceType(task.getSourceType().name());
        record.setClientRequestId(task.getClientRequestId());
        record.setRequestHash(task.getRequestHash());
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
        record.setTaskCreatedBy(item.getTaskCreatedBy());
        record.setAssetId(item.getAssetId());
        record.setFileName(item.getFileName());
        record.setFileHash(item.getFileHash());
        record.setSourceUrl(item.getSourceUrl());
        record.setParseAttempt(item.getParseAttempt());
        record.setDoclingRequestId(item.getDoclingRequestId());
        record.setDoclingJobId(item.getDoclingJobId());
        record.setSourceRevision(item.getSourceRevision());
        IngestionExecutionStage executionStage = resolveExecutionStage(item);
        record.setExecutionStage(executionStage.name());
        record.setExecutionEpoch(Math.max(1L, item.getExecutionEpoch()));
        record.setStageAttempt(Math.max(0, item.getStageAttempt()));
        record.setStageRetryCount(Math.max(0, item.getStageRetryCount()));
        record.setStageStartedAt(item.getStageStartedAt());
        record.setNextActionAt(resolveNextActionAt(item, executionStage));
        record.setLeaseToken(item.getLeaseToken());
        record.setLeaseUntil(item.getLeaseUntil());
        record.setParseRequestSnapshot(item.getParseRequestSnapshot());
        record.setParseResultObjectKey(item.getParseResultObjectKey());
        record.setEmbeddingResultObjectKey(item.getEmbeddingResultObjectKey());
        record.setStage(item.getStage().name());
        record.setStatus(item.getStatus().name());
        record.setProgress(item.getProgress());
        record.setDedupeStrategy(item.getDedupeStrategy() == null ? null : item.getDedupeStrategy().name());
        record.setDedupeResult(item.getDedupeResult() == null ? null : item.getDedupeResult().name());
        record.setDuplicateAssetId(item.getDuplicateAssetId());
        record.setErrorCode(item.getErrorCode());
        record.setErrorMessage(item.getErrorMessage());
        record.setCreatedAt(item.getCreatedAt());
        record.setUpdatedAt(item.getUpdatedAt());
        record.setFinishedAt(item.getFinishedAt());
        return record;
    }

    private IngestionTask toDomain(IngestionTaskRecord record, List<IngestionTaskItemRecord> itemRecords) {
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
                .items(itemRecords == null ? List.of() : itemRecords.stream().map(this::toDomain).toList())
                .build();
    }

    private IngestionTaskItem toDomain(IngestionTaskItemRecord record) {
        return IngestionTaskItem.builder()
                .id(record.getId())
                .taskId(record.getTaskId())
                .kbId(record.getKbId())
                .taskCreatedBy(record.getTaskCreatedBy())
                .assetId(record.getAssetId())
                .fileName(record.getFileName())
                .fileHash(record.getFileHash())
                .sourceUrl(record.getSourceUrl())
                .parseAttempt(record.getParseAttempt() == null ? 1 : record.getParseAttempt())
                .doclingRequestId(record.getDoclingRequestId())
                .doclingJobId(record.getDoclingJobId())
                .sourceRevision(record.getSourceRevision())
                .executionStage(parseExecutionStage(record))
                .executionEpoch(record.getExecutionEpoch() == null ? 1L : record.getExecutionEpoch())
                .stageAttempt(defaultInt(record.getStageAttempt()))
                .stageRetryCount(defaultInt(record.getStageRetryCount()))
                .stageStartedAt(record.getStageStartedAt())
                .nextActionAt(record.getNextActionAt())
                .leaseToken(record.getLeaseToken())
                .leaseUntil(record.getLeaseUntil())
                .parseRequestSnapshot(record.getParseRequestSnapshot())
                .parseResultObjectKey(record.getParseResultObjectKey())
                .embeddingResultObjectKey(record.getEmbeddingResultObjectKey())
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

    private DedupeResult parseDedupeResult(String value) {
        return value == null ? null : DedupeResult.valueOf(value);
    }

    private DedupeStrategy parseDedupeStrategy(String value) {
        return value == null ? null : DedupeStrategy.valueOf(value);
    }

    private IngestionExecutionStage parseExecutionStage(IngestionTaskItemRecord record) {
        if (record.getExecutionStage() != null) {
            return IngestionExecutionStage.valueOf(record.getExecutionStage());
        }
        if ("FAILED".equals(record.getStatus())) {
            return IngestionExecutionStage.FAILED;
        }
        if ("SUCCESS".equals(record.getStatus()) || "SKIPPED".equals(record.getStatus())) {
            return IngestionExecutionStage.COMPLETE;
        }
        return IngestionExecutionStage.PARSE_SUBMIT;
    }

    private IngestionExecutionStage resolveExecutionStage(IngestionTaskItem item) {
        if (item.getExecutionStage() != null) {
            IngestionExecutionStage executionStage = item.getExecutionStage();
            validateExplicitExecutionStage(item, executionStage);
            return executionStage;
        }
        if (item.getStatus() == IngestionTaskItemStatus.FAILED) {
            return IngestionExecutionStage.FAILED;
        }
        if (item.getStatus() == IngestionTaskItemStatus.SUCCESS
                || item.getStatus() == IngestionTaskItemStatus.SKIPPED) {
            return IngestionExecutionStage.COMPLETE;
        }
        // Public stage is only a client projection and does not prove that a durable upstream
        // artifact exists. Fresh REEMBED/maintenance tasks historically rebuild from the source,
        // so they must enter PARSE_SUBMIT unless the creator explicitly supplies an internal
        // execution stage and its required artifact pointers.
        return IngestionExecutionStage.PARSE_SUBMIT;
    }

    private void validateExplicitExecutionStage(IngestionTaskItem item,
                                                IngestionExecutionStage executionStage) {
        if (executionStage == IngestionExecutionStage.EMBED
                && !hasText(item.getParseResultObjectKey())) {
            throw new IllegalArgumentException(
                    "An ingestion item cannot start at EMBED without a parse artifact.");
        }
        if (executionStage == IngestionExecutionStage.INDEX
                && (!hasText(item.getParseResultObjectKey())
                || !hasText(item.getEmbeddingResultObjectKey()))) {
            throw new IllegalArgumentException(
                    "An ingestion item cannot start at INDEX without parse and embedding artifacts.");
        }
    }

    private LocalDateTime resolveNextActionAt(IngestionTaskItem item, IngestionExecutionStage executionStage) {
        if (executionStage.isTerminal()) {
            return null;
        }
        if (item.getNextActionAt() != null) {
            return item.getNextActionAt();
        }
        return item.getUpdatedAt() == null ? item.getCreatedAt() : item.getUpdatedAt();
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

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
