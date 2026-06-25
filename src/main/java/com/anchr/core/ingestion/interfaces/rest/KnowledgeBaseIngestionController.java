package com.anchr.core.ingestion.interfaces.rest;

import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.ingestion.application.IngestionApplicationService;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import com.anchr.core.ingestion.interfaces.rest.dto.DocumentMaintenanceTaskDTO;
import com.anchr.core.ingestion.interfaces.rest.dto.IngestionTaskCreateItemDTO;
import com.anchr.core.ingestion.interfaces.rest.dto.IngestionTaskCreateRequestDTO;
import com.anchr.core.ingestion.interfaces.rest.dto.IngestionTaskDTO;
import com.anchr.core.ingestion.interfaces.rest.dto.IngestionTaskListDTO;
import com.anchr.core.ingestion.interfaces.rest.dto.IngestionTaskSummaryDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified knowledge base ingestion task APIs.
 */
@Validated
@RestController
@RequestMapping("/api/v1/kbs/{kbId}")
@RequiredArgsConstructor
public class KnowledgeBaseIngestionController {

    private final IngestionApplicationService ingestionApplicationService;

    @RequireAuth
    @PostMapping("/ingestion-tasks")
    public Result<IngestionTaskDTO> createTask(@PathVariable @NotBlank String kbId,
                                               @Valid @RequestBody IngestionTaskCreateRequestDTO request) {
        return Result.success(IngestionTaskDTO.from(
                ingestionApplicationService.createTask(kbId, toCommand(request))));
    }

    @RequireAuth
    @GetMapping("/ingestion-tasks")
    public Result<IngestionTaskListDTO> listTasks(@PathVariable @NotBlank String kbId,
                                                  @RequestParam(required = false) IngestionTaskStatus status,
                                                  @RequestParam(defaultValue = "20") int limit) {
        return Result.success(IngestionTaskListDTO.builder()
                .items(ingestionApplicationService.listTasks(kbId, status, limit).stream()
                        .map(IngestionTaskSummaryDTO::from)
                        .toList())
                .nextCursor(null)
                .build());
    }

    @RequireAuth
    @GetMapping("/ingestion-tasks/{taskId}")
    public Result<IngestionTaskDTO> getTask(@PathVariable @NotBlank String kbId,
                                            @PathVariable @NotBlank String taskId) {
        return Result.success(IngestionTaskDTO.from(ingestionApplicationService.getTask(kbId, taskId)));
    }

    @RequireAuth
    @PostMapping("/ingestion-tasks/{taskId}/retry-failed")
    public Result<IngestionTaskDTO> retryFailed(@PathVariable @NotBlank String kbId,
                                                @PathVariable @NotBlank String taskId) {
        return Result.success(IngestionTaskDTO.from(ingestionApplicationService.retryFailed(kbId, taskId)));
    }

    @RequireAuth
    @PostMapping("/ingestion-tasks/{taskId}/items/{itemId}/retry")
    public Result<IngestionTaskDTO> retryItem(@PathVariable @NotBlank String kbId,
                                              @PathVariable @NotBlank String taskId,
                                              @PathVariable @NotBlank String itemId) {
        return Result.success(IngestionTaskDTO.from(ingestionApplicationService.retryItem(kbId, taskId, itemId)));
    }

    @RequireAuth
    @PostMapping("/documents/{assetId}/reparse")
    public Result<DocumentMaintenanceTaskDTO> reparse(@PathVariable @NotBlank String kbId,
                                                      @PathVariable @NotBlank String assetId) {
        return Result.success(DocumentMaintenanceTaskDTO.from(
                ingestionApplicationService.createReparseTask(kbId, assetId), assetId));
    }

    @RequireAuth
    @PostMapping("/documents/{assetId}/reembed")
    public Result<DocumentMaintenanceTaskDTO> reembed(@PathVariable @NotBlank String kbId,
                                                      @PathVariable @NotBlank String assetId) {
        return Result.success(DocumentMaintenanceTaskDTO.from(
                ingestionApplicationService.createReembedTask(kbId, assetId), assetId));
    }

    private IngestionApplicationService.IngestionCreateCommand toCommand(IngestionTaskCreateRequestDTO request) {
        return new IngestionApplicationService.IngestionCreateCommand(
                request.getSourceType(),
                request.getDedupeStrategy(),
                request.getItems().stream().map(this::toCommand).toList());
    }

    private IngestionApplicationService.IngestionCreateItemCommand toCommand(IngestionTaskCreateItemDTO item) {
        return new IngestionApplicationService.IngestionCreateItemCommand(
                item.getFileName(),
                item.getTitle(),
                item.getFileType(),
                item.getMimeType(),
                item.getSizeBytes(),
                item.getObjectKey(),
                item.getFileHash(),
                item.getSourceUrl());
    }
}
