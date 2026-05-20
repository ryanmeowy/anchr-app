package com.anchr.core.ingestion.application;

import com.anchr.core.ingestion.interfaces.rest.dto.BatchProcessDTO;
import com.anchr.core.ingestion.interfaces.rest.dto.BatchTaskStatusDTO;
import com.anchr.core.ingestion.interfaces.rest.dto.BatchUploadResultDTO;

import java.util.List;

/**
 * Image data processing service
 * Provides functionality for processing and indexing image data in the system
 * Main operations include batch processing of images that have been uploaded to OSS
 *
 * @author Ryan
 * @since 2025/12/15
 */
public interface ImageIngestionService {

    /**
     * Processes a batch of image items for vector indexing and storage
     *
     * @param items list of batch process requests containing OSS keys and metadata
     * @return BatchUploadResultDTO containing processing statistics and results
     */
    BatchUploadResultDTO processBatchItems(List<BatchProcessDTO> items);

    /**
     * Submit async batch task.
     */
    BatchTaskStatusDTO submitBatchTask(List<BatchProcessDTO> items);

    /**
     * Query async batch task status.
     */
    BatchTaskStatusDTO getBatchTaskStatus(String taskId);

    /**
     * Retry one failed image item in a batch task.
     */
    BatchTaskStatusDTO retryBatchTaskItem(String taskId, String itemId);

    /**
     * Retry all failed image items in a batch task.
     */
    BatchTaskStatusDTO retryAllFailedBatchTaskItems(String taskId);
}
