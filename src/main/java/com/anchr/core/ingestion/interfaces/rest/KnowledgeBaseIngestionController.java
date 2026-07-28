package com.anchr.core.ingestion.interfaces.rest;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.UploadCleanupContract;
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
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @RequireAuth(roles = {"ADMIN", "USER"})
    @UploadCleanupContract(safeBusinessErrors = {
            ApiError.INVALID_REQUEST,
            ApiError.KNOWLEDGE_BASE_NOT_FOUND
    })
    @PostMapping("/ingestion-tasks")
    public ResponseEntity<Result<IngestionTaskDTO>> createTask(
            @PathVariable @NotBlank String kbId,
            @Valid @RequestBody IngestionTaskCreateRequestDTO request) {
        var createResult = ingestionApplicationService.createTask(kbId, toCommand(request));
        HttpStatus status = createResult.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(Result.success(
                status.value(), IngestionTaskDTO.from(createResult.task())));
    }

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
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

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    @GetMapping("/ingestion-tasks/{taskId}")
    public Result<IngestionTaskDTO> getTask(@PathVariable @NotBlank String kbId,
                                            @PathVariable @NotBlank String taskId) {
        return Result.success(IngestionTaskDTO.from(ingestionApplicationService.getTask(kbId, taskId)));
    }

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    @GetMapping("/ingestion-tasks/by-client-request/{clientRequestId}")
    public ResponseEntity<Result<IngestionTaskDTO>> getTaskByClientRequestId(
            @PathVariable @NotBlank String kbId,
            @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+")
            String clientRequestId,
            HttpServletResponse servletResponse) {
        servletResponse.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Result.success(IngestionTaskDTO.from(
                        ingestionApplicationService.getTaskByClientRequestId(kbId, clientRequestId))));
    }

    @RequireAuth(roles = {"ADMIN", "USER"})
    @PostMapping("/ingestion-tasks/{taskId}/retry-failed")
    public Result<IngestionTaskDTO> retryFailed(@PathVariable @NotBlank String kbId,
                                                @PathVariable @NotBlank String taskId) {
        return Result.success(IngestionTaskDTO.from(ingestionApplicationService.retryFailed(kbId, taskId)));
    }

    @RequireAuth(roles = {"ADMIN", "USER"})
    @PostMapping("/ingestion-tasks/{taskId}/items/{itemId}/retry")
    public Result<IngestionTaskDTO> retryItem(@PathVariable @NotBlank String kbId,
                                              @PathVariable @NotBlank String taskId,
                                              @PathVariable @NotBlank String itemId) {
        return Result.success(IngestionTaskDTO.from(ingestionApplicationService.retryItem(kbId, taskId, itemId)));
    }

    @RequireAuth(roles = {"ADMIN", "USER"})
    @PostMapping("/documents/{assetId}/reparse")
    public Result<DocumentMaintenanceTaskDTO> reparse(@PathVariable @NotBlank String kbId,
                                                      @PathVariable @NotBlank String assetId) {
        return Result.success(DocumentMaintenanceTaskDTO.from(
                ingestionApplicationService.createReparseTask(kbId, assetId), assetId));
    }

    @RequireAuth(roles = {"ADMIN", "USER"})
    @PostMapping("/documents/{assetId}/reembed")
    public Result<DocumentMaintenanceTaskDTO> reembed(@PathVariable @NotBlank String kbId,
                                                      @PathVariable @NotBlank String assetId) {
        return Result.success(DocumentMaintenanceTaskDTO.from(
                ingestionApplicationService.createReembedTask(kbId, assetId), assetId));
    }

    private IngestionApplicationService.IngestionCreateCommand toCommand(IngestionTaskCreateRequestDTO request) {
        return new IngestionApplicationService.IngestionCreateCommand(
                request.getClientRequestId(),
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
                item.getFileHash());
    }
}
