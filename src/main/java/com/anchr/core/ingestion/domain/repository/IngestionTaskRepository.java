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

    Optional<IngestionTask> findById(String workspaceId, String kbId, String taskId);

    List<IngestionTask> list(String workspaceId, String kbId, IngestionTaskStatus status, int limit);

    List<IngestionTask> listRecent(String workspaceId, int limit);

    List<IngestionTaskItem> listItems(String taskId);

    List<IngestionTaskItem> listFailedItems(String workspaceId, String kbId, String taskId);

    Optional<IngestionTaskItem> findItem(String workspaceId, String kbId, String taskId, String itemId);

    boolean resetFailedItem(String workspaceId, String kbId, String taskId, String itemId, LocalDateTime updatedAt);

    boolean resetFailedItems(String workspaceId, String kbId, String taskId, LocalDateTime updatedAt);

    void refreshSummary(String workspaceId, String kbId, String taskId, String updatedBy, LocalDateTime updatedAt);
}
