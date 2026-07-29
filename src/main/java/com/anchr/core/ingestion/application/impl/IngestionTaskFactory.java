package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;

import java.time.LocalDateTime;
import java.util.List;

final class IngestionTaskFactory {

    IngestionTask build(RequestUserContext context,
                        String kbId,
                        IngestionSourceType sourceType,
                        List<IngestionTaskItem> items,
                        LocalDateTime now) {
        return build(context, kbId, sourceType, items, now, null, null);
    }

    IngestionTask build(RequestUserContext context,
                        String kbId,
                        IngestionSourceType sourceType,
                        List<IngestionTaskItem> items,
                        LocalDateTime now,
                        String clientRequestId,
                        String requestHash) {
        int successCount = (int) items.stream()
                .filter(item -> item.getStatus() == IngestionTaskItemStatus.SUCCESS
                        || item.getStatus() == IngestionTaskItemStatus.SKIPPED)
                .count();
        int failureCount = (int) items.stream()
                .filter(item -> item.getStatus() == IngestionTaskItemStatus.FAILED)
                .count();
        int runningCount = (int) items.stream()
                .filter(item -> item.getStatus() == IngestionTaskItemStatus.RUNNING)
                .count();
        return IngestionTask.builder()
                .id(items.getFirst().getTaskId())
                .kbId(kbId)
                .sourceType(sourceType)
                .clientRequestId(clientRequestId)
                .requestHash(requestHash)
                .status(resolveStatus(items, successCount, failureCount, runningCount))
                .totalCount(items.size())
                .successCount(successCount)
                .failureCount(failureCount)
                .runningCount(runningCount)
                .createdBy(context.userId())
                .updatedBy(context.userId())
                .createdAt(now)
                .updatedAt(now)
                .finishedAt(hasPendingOrRunning(items) ? null : now)
                .items(items)
                .build();
    }

    private IngestionTaskStatus resolveStatus(List<IngestionTaskItem> items,
                                              int successCount,
                                              int failureCount,
                                              int runningCount) {
        if (runningCount > 0) {
            return IngestionTaskStatus.RUNNING;
        }
        if (items.stream().anyMatch(item -> item.getStatus() == IngestionTaskItemStatus.PENDING)) {
            return IngestionTaskStatus.PENDING;
        }
        if (failureCount == 0) {
            return IngestionTaskStatus.SUCCESS;
        }
        if (successCount == 0) {
            return IngestionTaskStatus.FAILED;
        }
        return IngestionTaskStatus.PARTIAL_SUCCESS;
    }

    private boolean hasPendingOrRunning(List<IngestionTaskItem> items) {
        return items.stream().anyMatch(item -> item.getStatus() == IngestionTaskItemStatus.PENDING
                || item.getStatus() == IngestionTaskItemStatus.RUNNING);
    }
}
