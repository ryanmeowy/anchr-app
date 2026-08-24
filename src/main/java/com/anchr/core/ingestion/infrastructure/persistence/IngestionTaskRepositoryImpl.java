package com.anchr.core.ingestion.infrastructure.persistence;

import com.anchr.core.ingestion.domain.model.DedupeResult;
import com.anchr.core.ingestion.domain.model.DedupeStrategy;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** MyBatis implementation backed only by ingestion_task and ingestion_task_item. */
@Repository
@RequiredArgsConstructor
public class IngestionTaskRepositoryImpl implements IngestionTaskRepository {

    private static final String SCHEDULER_USER = "ingestion-scheduler";

    private final IngestionTaskMapper mapper;

    @Override
    public void save(IngestionTask task) {
        List<IngestionTaskItemRecord> items = task.getItems() == null
                ? List.of() : task.getItems().stream().map(this::toRecord).toList();
        mapper.insertTask(toRecord(task, resolveTaskDedupeStrategy(task)));
        items.forEach(mapper::insertItem);
    }

    @Override
    public Optional<IngestionTask> findById(String kbId, String taskId) {
        return mapper.findTask(kbId, taskId)
                .map(record -> toDomain(record, loadItems(record)));
    }

    @Override
    public Optional<IngestionTask> findByClientRequestId(String createdBy, String clientRequestId) {
        return mapper.findTaskByClientRequestId(createdBy, clientRequestId)
                .map(record -> toDomain(record, loadItems(record)));
    }

    @Override
    public List<IngestionTask> list(String kbId, IngestionTaskStatus status, int limit) {
        List<IngestionTaskRecord> tasks = mapper.listTasks(
                kbId, status == null ? null : status.name(), positiveLimit(limit));
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<String> taskIds = tasks.stream().map(IngestionTaskRecord::getId).toList();
        Map<String, List<IngestionTaskItemRecord>> itemsByTaskId =
                mapper.listItemsByTaskIds(taskIds).stream()
                        .collect(Collectors.groupingBy(IngestionTaskItemRecord::getTaskId));
        return tasks.stream()
                .map(record -> toDomain(
                        record, itemsByTaskId.getOrDefault(record.getId(), List.of())))
                .toList();
    }

    @Override
    public List<IngestionTask> listRecent(int limit) {
        return mapper.listRecentTasks(positiveLimit(limit)).stream()
                .map(record -> toDomain(record, List.of()))
                .toList();
    }

    @Override
    public List<IngestionTaskItem> listFailedItems(String kbId, String taskId) {
        return mapper.listFailedItems(kbId, taskId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<IngestionTaskItem> listRunningItems() {
        return mapper.listRunningItems().stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<IngestionTaskItem> findItem(String kbId, String taskId, String itemId) {
        return mapper.findItem(kbId, taskId, itemId).map(this::toDomain);
    }

    @Override
    public Optional<IngestionTaskItem> findRetryItem(
            String kbId, String taskId, String itemId) {
        return mapper.findRetryItem(kbId, taskId, itemId).map(this::toDomain);
    }

    @Override
    public List<String> listPendingItemIds(int limit) {
        return mapper.listPendingItemIds(positiveLimit(limit));
    }

    @Override
    public List<String> listPendingItemIds(String taskId, int limit) {
        return mapper.listPendingItemIdsByTask(taskId, positiveLimit(limit));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<IngestionTaskItem> claimPending(String itemId) {
        if (mapper.claimPending(itemId) != 1) {
            return Optional.empty();
        }
        IngestionTaskItemRecord claimed = mapper.findRunningItem(itemId)
                .orElseThrow(() -> new IllegalStateException(
                        "Claimed ingestion item disappeared before commit."));
        mapper.refreshSummary(claimed.getKbId(), claimed.getTaskId(),
                updatedBy(claimed), claimed.getUpdatedAt());
        return Optional.of(toDomain(claimed));
    }

    @Override
    public boolean advanceRunningItem(String kbId, String taskId, String itemId,
                                      IngestionStage expectedStage, IngestionStage nextStage,
                                      int progress, LocalDateTime updatedAt) {
        return mapper.advanceRunningItem(kbId, taskId, itemId,
                expectedStage.name(), nextStage.name(), progress, updatedAt) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isRunningForUpdate(String itemId, IngestionStage expectedStage) {
        return mapper.findRunningItemForUpdate(itemId, expectedStage.name()).isPresent();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean completeRunningItem(String kbId, String taskId, String itemId,
                                       IngestionStage expectedStage,
                                       String updatedBy, LocalDateTime updatedAt) {
        if (mapper.completeRunningItem(
                kbId, taskId, itemId, expectedStage.name(), updatedAt) != 1) {
            return false;
        }
        mapper.refreshSummary(kbId, taskId, fallbackUser(updatedBy), updatedAt);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean failRunningItem(String kbId, String taskId, String itemId,
                                   IngestionStage expectedStage, int progress,
                                   String errorCode, String errorMessage,
                                   String updatedBy, LocalDateTime updatedAt) {
        if (mapper.failRunningItem(kbId, taskId, itemId, expectedStage.name(),
                progress, errorCode, errorMessage, updatedAt) != 1) {
            return false;
        }
        mapper.refreshSummary(kbId, taskId, fallbackUser(updatedBy), updatedAt);
        return true;
    }

    @Override
    public boolean resetFailedItem(String kbId, String taskId, String itemId,
                                   long nextTargetIndexGeneration,
                                   LocalDateTime updatedAt) {
        return mapper.resetFailedItem(
                kbId, taskId, itemId, nextTargetIndexGeneration, updatedAt) == 1;
    }

    @Override
    public long findMaxTargetIndexGeneration(String assetId) {
        Long value = mapper.findMaxTargetIndexGeneration(assetId);
        return value == null ? 0L : Math.max(0L, value);
    }

    @Override
    public List<Long> listTargetIndexGenerations(String assetId) {
        return mapper.listTargetIndexGenerations(assetId);
    }

    @Override
    public Optional<Long> findTargetIndexGeneration(String itemId, String assetId) {
        return mapper.findTargetIndexGeneration(itemId, assetId);
    }

    @Override
    public boolean assignTargetIndexGeneration(String itemId, String assetId,
                                               long targetIndexGeneration,
                                               LocalDateTime updatedAt) {
        return mapper.assignTargetIndexGeneration(
                itemId, assetId, targetIndexGeneration, updatedAt) == 1;
    }

    @Override
    public void refreshSummary(String kbId, String taskId,
                               String updatedBy, LocalDateTime updatedAt) {
        mapper.refreshSummary(kbId, taskId, fallbackUser(updatedBy), updatedAt);
    }

    private IngestionTaskRecord toRecord(IngestionTask task, DedupeStrategy strategy) {
        IngestionTaskRecord record = new IngestionTaskRecord();
        record.setId(task.getId());
        record.setKbId(task.getKbId());
        record.setSourceType(task.getSourceType().name());
        record.setClientRequestId(task.getClientRequestId());
        record.setRequestHash(task.getRequestHash());
        record.setDedupeStrategy(strategy == null ? null : strategy.name());
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
        record.setTargetIndexGeneration(item.getTargetIndexGeneration());
        record.setFileName(item.getFileName());
        record.setFileHash(item.getFileHash());
        record.setStage(item.getStage().name());
        record.setStatus(item.getStatus().name());
        record.setProgress(item.getProgress());
        record.setDedupeStrategy(item.getDedupeStrategy() == null
                ? null : item.getDedupeStrategy().name());
        record.setDedupeResult(item.getDedupeResult() == null
                ? null : item.getDedupeResult().name());
        record.setDuplicateAssetId(item.getDuplicateAssetId());
        record.setErrorCode(item.getErrorCode());
        record.setErrorMessage(item.getErrorMessage());
        record.setCreatedAt(item.getCreatedAt());
        record.setUpdatedAt(item.getUpdatedAt());
        record.setFinishedAt(item.getFinishedAt());
        return record;
    }

    private IngestionTask toDomain(IngestionTaskRecord record,
                                   List<IngestionTaskItemRecord> items) {
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
                .items(items == null ? List.of() : items.stream()
                        .map(item -> toDomain(item, record)).toList())
                .build();
    }

    private IngestionTaskItem toDomain(IngestionTaskItemRecord record) {
        return toDomain(record, record.getKbId(), record.getTaskCreatedBy(),
                record.getDedupeStrategy());
    }

    private IngestionTaskItem toDomain(IngestionTaskItemRecord record,
                                       IngestionTaskRecord task) {
        return toDomain(record, task.getKbId(), task.getCreatedBy(), task.getDedupeStrategy());
    }

    private IngestionTaskItem toDomain(IngestionTaskItemRecord record,
                                       String kbId, String taskCreatedBy,
                                       String dedupeStrategy) {
        return IngestionTaskItem.builder()
                .id(record.getId())
                .taskId(record.getTaskId())
                .kbId(kbId)
                .taskCreatedBy(taskCreatedBy)
                .assetId(record.getAssetId())
                .targetIndexGeneration(record.getTargetIndexGeneration())
                .fileName(record.getFileName())
                .fileHash(record.getFileHash())
                .stage(IngestionStage.valueOf(record.getStage()))
                .status(IngestionTaskItemStatus.valueOf(record.getStatus()))
                .progress(defaultInt(record.getProgress()))
                .dedupeStrategy(parseDedupeStrategy(dedupeStrategy))
                .dedupeResult(parseDedupeResult(record.getDedupeResult()))
                .duplicateAssetId(record.getDuplicateAssetId())
                .errorCode(record.getErrorCode())
                .errorMessage(record.getErrorMessage())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .finishedAt(record.getFinishedAt())
                .build();
    }

    private List<IngestionTaskItemRecord> loadItems(IngestionTaskRecord task) {
        return mapper.listItemsByTaskIds(List.of(task.getId()));
    }

    private DedupeStrategy resolveTaskDedupeStrategy(IngestionTask task) {
        DedupeStrategy result = null;
        if (task.getItems() == null) return null;
        for (IngestionTaskItem item : task.getItems()) {
            if (item == null || item.getDedupeStrategy() == null) continue;
            if (result != null && result != item.getDedupeStrategy()) {
                throw new IllegalArgumentException(
                        "All ingestion items in one task must use the same dedupe strategy.");
            }
            result = item.getDedupeStrategy();
        }
        return result;
    }

    private DedupeResult parseDedupeResult(String value) {
        return value == null ? null : DedupeResult.valueOf(value);
    }

    private DedupeStrategy parseDedupeStrategy(String value) {
        return value == null ? null : DedupeStrategy.valueOf(value);
    }

    private String updatedBy(IngestionTaskItemRecord record) {
        return fallbackUser(record.getTaskCreatedBy());
    }

    private String fallbackUser(String value) {
        return value == null || value.isBlank() ? SCHEDULER_USER : value;
    }

    private int positiveLimit(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return limit;
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}
