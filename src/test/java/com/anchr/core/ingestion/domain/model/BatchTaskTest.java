package com.anchr.core.ingestion.domain.model;

import com.anchr.core.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchTaskTest {

    @Test
    void retryItem_shouldResetFailedItemAndIncreaseRetryCount() {
        long now = 1_000L;
        BatchTaskItem item = buildItem("item-1", BatchTaskItemStatus.FAILED, 0, now);
        BatchTask task = BatchTask.createPending("task-1", List.of(item), now);
        task.refreshSummary(now + 1);

        task.retryItem("item-1", now + 2);

        BatchTaskItem updated = task.findItemOrThrow("item-1");
        assertThat(updated.getStatus()).isEqualTo(BatchTaskItemStatus.PENDING);
        assertThat(updated.getRetryCount()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(BatchTaskStatus.PENDING);
    }

    @Test
    void retryAllFailed_shouldThrowWhenNoFailedItems() {
        long now = 1_000L;
        BatchTaskItem item = buildItem("item-1", BatchTaskItemStatus.SUCCESS, 0, now);
        BatchTask task = BatchTask.createPending("task-1", List.of(item), now);
        task.refreshSummary(now + 1);

        assertThatThrownBy(() -> task.retryAllFailed(now + 2))
                .isInstanceOf(BusinessException.class)
                .hasMessage("No FAILED items to retry");
    }

    @Test
    void refreshSummary_shouldSetPartialFailedWhenAllDoneAndMixedResult() {
        long now = 1_000L;
        BatchTaskItem success = buildItem("item-1", BatchTaskItemStatus.SUCCESS, 0, now);
        BatchTaskItem failed = buildItem("item-2", BatchTaskItemStatus.FAILED, 0, now);
        BatchTask task = BatchTask.createPending("task-1", List.of(success, failed), now);

        task.refreshSummary(now + 1);

        assertThat(task.getStatus()).isEqualTo(BatchTaskStatus.PARTIAL_FAILED);
        assertThat(task.getSuccessCount()).isEqualTo(1);
        assertThat(task.getFailureCount()).isEqualTo(1);
        assertThat(task.getCompletedAt()).isEqualTo(now + 1);
    }

    private BatchTaskItem buildItem(String itemId, BatchTaskItemStatus status, int retryCount, long updatedAt) {
        BatchTaskItem item = new BatchTaskItem();
        item.setItemId(itemId);
        item.setKey("k");
        item.setFileName("f.png");
        item.setFileHash("h");
        item.setStatus(status);
        item.setRetryCount(retryCount);
        item.setUpdatedAt(updatedAt);
        return item;
    }
}
