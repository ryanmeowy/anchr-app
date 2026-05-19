package com.smart.vision.core.ingestion.interfaces.rest;

import com.smart.vision.core.auth.RequireAuth;
import com.smart.vision.core.common.model.Result;
import com.smart.vision.core.ingestion.interfaces.rest.dto.BatchProcessDTO;
import com.smart.vision.core.ingestion.interfaces.rest.dto.BatchTaskStatusDTO;
import com.smart.vision.core.ingestion.interfaces.rest.dto.BatchUploadResultDTO;
import com.smart.vision.core.ingestion.application.ImageIngestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API controller for image upload functionality
 * Provides endpoints for uploading images, processing them through OSS, and indexing in Elasticsearch
 *
 * @author Ryan
 * @since 2025/12/15
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/image")
@Validated
@RequiredArgsConstructor
public class ImageApiController {
    private static final int MAX_BATCH_ITEMS = 20;

    private final ImageIngestionService ingestionService;

    /**
     * Batch processing api (called after frontend direct upload to OSS)
     * Deprecated: use /api/v1/image/batch-tasks for async task flow.
     *
     * @param items list of batch process requests containing OSS keys and file names
     * @return Result containing batch upload statistics
     */
    @Deprecated
    @RequireAuth
    @PostMapping("/batch-process")
    public Result<BatchUploadResultDTO> batchProcess(
            @RequestBody @Valid @Size(max = MAX_BATCH_ITEMS, message = "The number of items processed per request cannot exceed 20")
            List<@Valid BatchProcessDTO> items) {
        BatchUploadResultDTO result = ingestionService.processBatchItems(items);
        return Result.success(result);
    }

    @RequireAuth
    @PostMapping("/batch-tasks")
    public Result<BatchTaskStatusDTO> createBatchTask(
            @RequestBody @Valid @Size(max = MAX_BATCH_ITEMS, message = "The number of items processed per request cannot exceed 20")
            List<@Valid BatchProcessDTO> items) {
        BatchTaskStatusDTO task = ingestionService.submitBatchTask(items);
        return Result.success(task);
    }

    @RequireAuth
    @GetMapping("/batch-tasks/{taskId}")
    public Result<BatchTaskStatusDTO> getBatchTask(@PathVariable String taskId) {
        BatchTaskStatusDTO task = ingestionService.getBatchTaskStatus(taskId);
        return Result.success(task);
    }

    @RequireAuth
    @PostMapping("/batch-tasks/{taskId}/items/{itemId}/retry")
    public Result<BatchTaskStatusDTO> retryBatchTaskItem(@PathVariable String taskId, @PathVariable String itemId) {
        BatchTaskStatusDTO task = ingestionService.retryBatchTaskItem(taskId, itemId);
        return Result.success(task);
    }

    @RequireAuth
    @PostMapping("/batch-tasks/{taskId}/retry-failed")
    public Result<BatchTaskStatusDTO> retryAllFailedBatchTaskItems(@PathVariable String taskId) {
        BatchTaskStatusDTO task = ingestionService.retryAllFailedBatchTaskItems(taskId);
        return Result.success(task);
    }
}
