package com.anchr.core.kb.infrastructure.persistence;

import com.anchr.core.kb.domain.model.ingestion.DedupeResult;
import com.anchr.core.kb.domain.model.ingestion.IngestionSourceType;
import com.anchr.core.kb.domain.model.ingestion.IngestionStage;
import com.anchr.core.kb.domain.model.ingestion.IngestionTask;
import com.anchr.core.kb.domain.model.ingestion.IngestionTaskItem;
import com.anchr.core.kb.domain.model.ingestion.IngestionTaskItemStatus;
import com.anchr.core.kb.domain.model.ingestion.IngestionTaskStatus;
import com.anchr.core.kb.domain.repository.ingestion.IngestionTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis implementation for ingestion task repository.
 */
@Repository
@RequiredArgsConstructor
public class MyBatisIngestionTaskRepository implements IngestionTaskRepository {

    private final IngestionTaskMapper mapper;

    @Override
    public void save(IngestionTask task) {
        mapper.insertTask(toRecord(task));
        if (task.getItems() == null) {
            return;
        }
        for (IngestionTaskItem item : task.getItems()) {
            mapper.insertItem(toRecord(item));
        }
    }

    @Override
    public Optional<IngestionTask> findById(String workspaceId, String kbId, String taskId) {
        Optional<IngestionTaskRecord> task = mapper.findTask(workspaceId, kbId, taskId);
        return task.map(record -> toDomain(record, mapper.listItems(taskId)));
    }

    @Override
    public List<IngestionTask> list(String workspaceId, String kbId, IngestionTaskStatus status, int limit) {
        String statusValue = status == null ? null : status.name();
        return mapper.listTasks(workspaceId, kbId, statusValue, limit).stream()
                .map(record -> toDomain(record, List.of()))
                .toList();
    }

    @Override
    public List<IngestionTask> listRecent(String workspaceId, int limit) {
        return mapper.listRecentTasks(workspaceId, limit).stream()
                .map(record -> toDomain(record, List.of()))
                .toList();
    }

    @Override
    public List<IngestionTaskItem> listItems(String taskId) {
        return mapper.listItems(taskId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<IngestionTaskItem> listFailedItems(String workspaceId, String kbId, String taskId) {
        return mapper.listFailedItems(workspaceId, kbId, taskId).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<IngestionTaskItem> findItem(String workspaceId, String kbId, String taskId, String itemId) {
        return mapper.findItem(workspaceId, kbId, taskId, itemId).map(this::toDomain);
    }

    @Override
    public boolean resetFailedItem(String workspaceId, String kbId, String taskId,
                                   String itemId, LocalDateTime updatedAt) {
        return mapper.resetFailedItem(workspaceId, kbId, taskId, itemId, updatedAt) > 0;
    }

    @Override
    public boolean resetFailedItems(String workspaceId, String kbId, String taskId, LocalDateTime updatedAt) {
        return mapper.resetFailedItems(workspaceId, kbId, taskId, updatedAt) > 0;
    }

    @Override
    public void refreshSummary(String workspaceId, String kbId, String taskId, String updatedBy, LocalDateTime updatedAt) {
        mapper.refreshSummary(workspaceId, kbId, taskId, updatedBy, updatedAt);
    }

    private IngestionTaskRecord toRecord(IngestionTask task) {
        IngestionTaskRecord record = new IngestionTaskRecord();
        record.setId(task.getId());
        record.setWorkspaceId(task.getWorkspaceId());
        record.setKbId(task.getKbId());
        record.setSourceType(task.getSourceType().name());
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
        record.setDedupeResult(item.getDedupeResult() == null ? null : item.getDedupeResult().name());
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
                .workspaceId(record.getWorkspaceId())
                .kbId(record.getKbId())
                .sourceType(IngestionSourceType.valueOf(record.getSourceType()))
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
                .assetId(record.getAssetId())
                .fileName(record.getFileName())
                .fileHash(record.getFileHash())
                .sourceUrl(record.getSourceUrl())
                .stage(IngestionStage.valueOf(record.getStage()))
                .status(IngestionTaskItemStatus.valueOf(record.getStatus()))
                .progress(defaultInt(record.getProgress()))
                .dedupeResult(parseDedupeResult(record.getDedupeResult()))
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

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}
