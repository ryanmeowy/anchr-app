package com.anchr.core.ingestion.application;

import com.anchr.core.ingestion.interfaces.rest.dto.BatchTaskStatusDTO;
import com.anchr.core.ingestion.interfaces.rest.dto.TextBatchProcessDTO;

import java.util.List;

/**
 * Text asset ingestion service
 */
public interface TextAssetIngestionService {

    BatchTaskStatusDTO submitBatchTask(List<TextBatchProcessDTO> items);

    BatchTaskStatusDTO getBatchTaskStatus(String taskId);

    BatchTaskStatusDTO retryBatchTaskItem(String taskId, String itemId);

    BatchTaskStatusDTO retryAllFailedBatchTaskItems(String taskId);
}
