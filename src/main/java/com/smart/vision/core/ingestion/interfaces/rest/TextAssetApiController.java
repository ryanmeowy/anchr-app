package com.smart.vision.core.ingestion.interfaces.rest;

import com.smart.vision.core.common.model.Result;
import com.smart.vision.core.auth.RequireAuth;
import com.smart.vision.core.ingestion.application.TextAssetIngestionService;
import com.smart.vision.core.ingestion.interfaces.rest.dto.BatchTaskStatusDTO;
import com.smart.vision.core.ingestion.interfaces.rest.dto.TextBatchProcessDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Text asset ingestion APIs
 */
@RestController
@RequestMapping("/api/v1/ingestion/text-assets")
@Validated
@RequiredArgsConstructor
public class TextAssetApiController {

    private static final int MAX_BATCH_ITEMS = 20;

    private final TextAssetIngestionService textAssetIngestionService;

    @RequireAuth
    @PostMapping
    public Result<BatchTaskStatusDTO> createTextAsset(
            @RequestBody @Valid
            @Size(
                    max = MAX_BATCH_ITEMS,
                    message = "The number of items processed per request cannot exceed 20"
            )
            List<@Valid TextBatchProcessDTO> items) {
        return submitTask(items);
    }

    @RequireAuth
    @PostMapping("/batch-tasks")
    public Result<BatchTaskStatusDTO> createBatchTask(
            @RequestBody @Valid
            @Size(
                    max = MAX_BATCH_ITEMS,
                    message = "The number of items processed per request cannot exceed 20"
            )
            List<@Valid TextBatchProcessDTO> items) {
        return submitTask(items);
    }

    @RequireAuth
    @GetMapping("/batch-tasks/{taskId}")
    public Result<BatchTaskStatusDTO> getTaskStatus(@PathVariable @NotBlank String taskId) {
        return Result.success(textAssetIngestionService.getBatchTaskStatus(taskId));
    }

    @RequireAuth
    @PostMapping("/batch-tasks/{taskId}/items/{itemId}/retry")
    public Result<BatchTaskStatusDTO> retryTaskItem(@PathVariable @NotBlank String taskId,
                                                     @PathVariable @NotBlank String itemId) {
        return Result.success(textAssetIngestionService.retryBatchTaskItem(taskId, itemId));
    }

    @RequireAuth
    @PostMapping("/batch-tasks/{taskId}/retry-failed")
    public Result<BatchTaskStatusDTO> retryAllFailed(@PathVariable @NotBlank String taskId) {
        return Result.success(textAssetIngestionService.retryAllFailedBatchTaskItems(taskId));
    }

    private Result<BatchTaskStatusDTO> submitTask(List<@Valid TextBatchProcessDTO> items) {
        return Result.success(textAssetIngestionService.submitBatchTask(items));
    }
}
