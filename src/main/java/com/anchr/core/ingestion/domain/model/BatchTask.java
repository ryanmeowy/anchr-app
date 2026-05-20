package com.anchr.core.ingestion.domain.model;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Batch task aggregate root.
 */
@Data
@NoArgsConstructor
public class BatchTask {

    private String taskId;
    private BatchTaskStatus status;

    private int total;
    private int successCount;
    private int failureCount;
    private int runningCount;
    private int pendingCount;

    private long createdAt;
    private long updatedAt;
    private Long completedAt;

    private List<BatchTaskItem> items = new ArrayList<>();

    public static BatchTask createPending(String taskId, List<BatchTaskItem> items, long now) {
        BatchTask task = new BatchTask();
        task.setTaskId(taskId);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setCompletedAt(null);
        task.setItems(items == null ? new ArrayList<>() : new ArrayList<>(items));
        task.refreshSummary(now);
        return task;
    }

    public boolean hasPendingItems() {
        return items.stream().anyMatch(x -> x.getStatus() == BatchTaskItemStatus.PENDING);
    }

    public List<BatchTaskItem> pendingItems() {
        return items.stream().filter(x -> x.getStatus() == BatchTaskItemStatus.PENDING).toList();
    }

    public BatchTaskItem findItemOrThrow(String itemId) {
        return items.stream()
                .filter(item -> Objects.equals(itemId, item.getItemId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ApiError.INGEST_TASK_ITEM_NOT_FOUND));
    }

    public void ensureNotRunning() {
        if (status == BatchTaskStatus.RUNNING) {
            throw new BusinessException(ApiError.INGEST_TASK_RUNNING);
        }
    }

    public void retryItem(String itemId, long now) {
        ensureNotRunning();
        findItemOrThrow(itemId).retry(now);
        this.completedAt = null;
        refreshSummary(now);
    }

    public int retryAllFailed(long now) {
        ensureNotRunning();
        int changed = 0;
        for (BatchTaskItem item : items) {
            if (item.getStatus() != BatchTaskItemStatus.FAILED) {
                continue;
            }
            item.retry(now);
            changed++;
        }
        if (changed == 0) {
            throw new BusinessException(ApiError.INGEST_NO_FAILED_ITEMS);
        }
        this.completedAt = null;
        refreshSummary(now);
        return changed;
    }

    public void markItemRunning(String itemId, long now) {
        findItemOrThrow(itemId).markRunning(now);
        refreshSummary(now);
    }

    public void markItemSuccess(String itemId, long now) {
        findItemOrThrow(itemId).markSuccess(now);
        refreshSummary(now);
    }

    public void markItemFailed(String itemId, String message, long now) {
        findItemOrThrow(itemId).markFailed(message, now);
        refreshSummary(now);
    }

    public void refreshSummary(long now) {
        int totalCount = items.size();
        int success = 0;
        int failure = 0;
        int running = 0;
        int pending = 0;

        for (BatchTaskItem item : items) {
            if (item.getStatus() == BatchTaskItemStatus.SUCCESS) {
                success++;
            } else if (item.getStatus() == BatchTaskItemStatus.FAILED) {
                failure++;
            } else if (item.getStatus() == BatchTaskItemStatus.RUNNING) {
                running++;
            } else if (item.getStatus() == BatchTaskItemStatus.PENDING) {
                pending++;
            }
        }

        this.total = totalCount;
        this.successCount = success;
        this.failureCount = failure;
        this.runningCount = running;
        this.pendingCount = pending;
        this.updatedAt = now;

        if (pending == totalCount && running == 0 && success == 0 && failure == 0) {
            this.status = BatchTaskStatus.PENDING;
            this.completedAt = null;
            return;
        }

        if (pending == 0 && running == 0) {
            if (success == totalCount) {
                this.status = BatchTaskStatus.SUCCESS;
            } else if (failure == totalCount) {
                this.status = BatchTaskStatus.FAILED;
            } else {
                this.status = BatchTaskStatus.PARTIAL_FAILED;
            }
            this.completedAt = now;
            return;
        }

        this.status = BatchTaskStatus.RUNNING;
        this.completedAt = null;
    }
}
