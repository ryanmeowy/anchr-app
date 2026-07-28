package com.anchr.core.ingestion.domain.repository;

import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Repository boundary for the two-table ingestion model. */
public interface IngestionTaskRepository {

    void save(IngestionTask task);

    Optional<IngestionTask> findById(String kbId, String taskId);

    Optional<IngestionTask> findByClientRequestId(String createdBy, String clientRequestId);

    List<IngestionTask> list(String kbId, IngestionTaskStatus status, int limit);

    List<IngestionTask> listRecent(int limit);

    List<IngestionTaskItem> listItems(String taskId);

    List<IngestionTaskItem> listFailedItems(String kbId, String taskId);

    List<IngestionTaskItem> listRunningItems();

    Optional<IngestionTaskItem> findItem(String kbId, String taskId, String itemId);

    Optional<IngestionTaskItem> findRetryItem(String kbId, String taskId, String itemId);

    List<String> listPendingItemIds(int limit);

    List<String> listPendingItemIds(String taskId, int limit);

    /** Atomically changes one pending item to RUNNING/PARSE. */
    Optional<IngestionTaskItem> claimPending(String itemId);

    boolean advanceRunningItem(String kbId, String taskId, String itemId,
                               IngestionStage expectedStage, IngestionStage nextStage,
                               int progress, LocalDateTime updatedAt);

    boolean isRunningForUpdate(String itemId, IngestionStage expectedStage);

    boolean completeRunningItem(String kbId, String taskId, String itemId,
                                IngestionStage expectedStage,
                                String updatedBy, LocalDateTime updatedAt);

    boolean failRunningItem(String kbId, String taskId, String itemId,
                            IngestionStage expectedStage, int progress,
                            String errorCode, String errorMessage,
                            String updatedBy, LocalDateTime updatedAt);

    boolean resetFailedItem(String kbId, String taskId, String itemId,
                            long nextTargetIndexGeneration, LocalDateTime updatedAt);

    long findMaxTargetIndexGeneration(String assetId);

    List<Long> listTargetIndexGenerations(String assetId);

    Optional<Long> findTargetIndexGeneration(String itemId, String assetId);

    boolean assignTargetIndexGeneration(String itemId, String assetId,
                                        long targetIndexGeneration,
                                        LocalDateTime updatedAt);

    void refreshSummary(String kbId, String taskId, String updatedBy, LocalDateTime updatedAt);
}
