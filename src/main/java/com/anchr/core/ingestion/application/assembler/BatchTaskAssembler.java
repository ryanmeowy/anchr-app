package com.anchr.core.ingestion.application.assembler;

import com.anchr.core.ingestion.domain.model.BatchTask;
import com.anchr.core.ingestion.domain.model.BatchTaskItem;
import com.anchr.core.ingestion.domain.model.BatchTaskItemStatus;
import com.anchr.core.ingestion.domain.model.BatchTaskStatus;
import com.anchr.core.ingestion.domain.model.AssetType;
import com.anchr.core.ingestion.interfaces.rest.dto.BatchProcessDTO;
import com.anchr.core.ingestion.interfaces.rest.dto.BatchTaskStatusDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class BatchTaskAssembler {

    public BatchTaskStatusDTO toTaskDto(BatchTask task) {
        BatchTaskStatusDTO dto = new BatchTaskStatusDTO();
        dto.setTaskId(task.getTaskId());
        dto.setStatus(task.getStatus() == null ? BatchTaskStatus.PENDING.name() : task.getStatus().name());
        dto.setTotal(task.getTotal());
        dto.setSuccessCount(task.getSuccessCount());
        dto.setFailureCount(task.getFailureCount());
        dto.setRunningCount(task.getRunningCount());
        dto.setPendingCount(task.getPendingCount());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        dto.setCompletedAt(task.getCompletedAt());
        List<BatchTaskItem> items = task.getItems() == null ? List.of() : task.getItems();
        dto.setItems(items.stream().map(this::toTaskItemDto).toList());
        return dto;
    }

    public BatchTask toTaskDomain(BatchTaskStatusDTO dto) {
        BatchTask task = new BatchTask();
        task.setTaskId(dto.getTaskId());
        task.setStatus(parseTaskStatus(dto.getStatus()));
        task.setTotal(dto.getTotal());
        task.setSuccessCount(dto.getSuccessCount());
        task.setFailureCount(dto.getFailureCount());
        task.setRunningCount(dto.getRunningCount());
        task.setPendingCount(dto.getPendingCount());
        task.setCreatedAt(dto.getCreatedAt());
        task.setUpdatedAt(dto.getUpdatedAt());
        task.setCompletedAt(dto.getCompletedAt());
        List<BatchTaskStatusDTO.ItemStatus> items = dto.getItems() == null ? List.of() : dto.getItems();
        task.setItems(items.stream().map(this::toTaskItemDomain).toList());
        return task;
    }

    public BatchProcessDTO toBatchProcessDTO(BatchTaskItem itemStatus) {
        BatchProcessDTO item = new BatchProcessDTO();
        item.setKey(itemStatus.getKey());
        item.setFileName(itemStatus.getFileName());
        item.setFileHash(itemStatus.getFileHash());
        return item;
    }

    private BatchTaskStatusDTO.ItemStatus toTaskItemDto(BatchTaskItem item) {
        BatchTaskStatusDTO.ItemStatus dto = new BatchTaskStatusDTO.ItemStatus();
        dto.setItemId(item.getItemId());
        dto.setAssetType(item.getAssetType() == null ? null : item.getAssetType().name());
        dto.setKey(item.getKey());
        dto.setFileName(item.getFileName());
        dto.setFileHash(item.getFileHash());
        dto.setStatus(item.getStatus().name());
        dto.setErrorMessage(item.getErrorMessage());
        dto.setRetryCount(item.getRetryCount());
        dto.setUpdatedAt(item.getUpdatedAt());
        return dto;
    }

    private BatchTaskItem toTaskItemDomain(BatchTaskStatusDTO.ItemStatus dto) {
        BatchTaskItem item = new BatchTaskItem();
        item.setItemId(dto.getItemId());
        item.setAssetType(parseAssetType(dto.getAssetType()));
        item.setKey(dto.getKey());
        item.setFileName(dto.getFileName());
        item.setFileHash(dto.getFileHash());
        item.setStatus(parseTaskItemStatus(dto.getStatus()));
        item.setErrorMessage(dto.getErrorMessage());
        item.setRetryCount(dto.getRetryCount());
        item.setUpdatedAt(dto.getUpdatedAt());
        return item;
    }

    private BatchTaskStatus parseTaskStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return BatchTaskStatus.PENDING;
        }
        try {
            return BatchTaskStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return BatchTaskStatus.PENDING;
        }
    }

    private BatchTaskItemStatus parseTaskItemStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return BatchTaskItemStatus.PENDING;
        }
        try {
            return BatchTaskItemStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return BatchTaskItemStatus.PENDING;
        }
    }

    private AssetType parseAssetType(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return AssetType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
