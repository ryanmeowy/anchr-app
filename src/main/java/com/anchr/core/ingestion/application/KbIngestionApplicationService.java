package com.anchr.core.ingestion.application;

import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;

import java.util.List;

/**
 * Application service for unified knowledge base ingestion tasks.
 */
public interface KbIngestionApplicationService {

    IngestionTask createTask(String kbId, IngestionCreateCommand command);

    List<IngestionTask> listTasks(String kbId, IngestionTaskStatus status, int limit);

    IngestionTask getTask(String kbId, String taskId);

    IngestionTask retryItem(String kbId, String taskId, String itemId);

    IngestionTask retryFailed(String kbId, String taskId);

    IngestionTask createReparseTask(String kbId, String assetId);

    IngestionTask createReembedTask(String kbId, String assetId);

    record IngestionCreateCommand(IngestionSourceType sourceType,
                                  DedupeStrategy dedupeStrategy,
                                  List<IngestionCreateItemCommand> items) {
    }

    record IngestionCreateItemCommand(String fileName,
                                      String title,
                                      String fileType,
                                      String mimeType,
                                      Long sizeBytes,
                                      String objectKey,
                                      String fileHash,
                                      String sourceUrl) {
    }
}
