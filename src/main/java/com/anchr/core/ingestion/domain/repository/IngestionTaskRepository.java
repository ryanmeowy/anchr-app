package com.anchr.core.ingestion.domain.repository;

import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository boundary for ingestion tasks.
 */
public interface IngestionTaskRepository {

    void save(IngestionTask task);

    Optional<IngestionTask> findById(String kbId, String taskId);

    Optional<IngestionTask> findByClientRequestId(String createdBy, String clientRequestId);

    List<IngestionTask> list(String kbId, IngestionTaskStatus status, int limit);

    List<IngestionTask> listRecent(int limit);

    List<IngestionTaskItem> listItems(String taskId);

    List<IngestionTaskItem> listFailedItems(String kbId, String taskId);

    Optional<IngestionTaskItem> findItem(String kbId, String taskId, String itemId);

    boolean resetFailedItem(String kbId, String taskId, String itemId,
                            int expectedParseAttempt, int nextParseAttempt,
                            String nextDoclingRequestId, LocalDateTime updatedAt);

    boolean prepareParseAttempt(String kbId, String taskId, String itemId,
                                int parseAttempt, String doclingRequestId, String sourceRevision,
                                LocalDateTime updatedAt);

    boolean recordDoclingJob(String kbId, String taskId, String itemId,
                             String doclingRequestId, String doclingJobId,
                             LocalDateTime updatedAt);

    boolean markItemRunning(String kbId, String taskId, String itemId,
                            String stage, int progress, LocalDateTime updatedAt);

    boolean markItemSuccess(String kbId, String taskId, String itemId,
                            String stage, int progress, LocalDateTime updatedAt);

    boolean markItemFailed(String kbId, String taskId, String itemId,
                           String stage, int progress, String errorCode, String errorMessage,
                           LocalDateTime updatedAt);

    void refreshSummary(String kbId, String taskId, String updatedBy, LocalDateTime updatedAt);
}
