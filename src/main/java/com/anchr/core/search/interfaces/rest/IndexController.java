package com.anchr.core.search.interfaces.rest;

import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.search.application.SegmentIndexManager;
import com.anchr.core.search.interfaces.rest.dto.IndexRebuildConfirmRequest;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Index management endpoints for kb_segment lifecycle.
 */
@RestController
@RequestMapping("/api/v1/index")
@RequiredArgsConstructor
public class IndexController {

    private final SegmentIndexManager segmentIndexManager;

    @RequireAuth(roles = {"OWNER", "GUEST"})
    @GetMapping("/status")
    public Result<SegmentIndexStatusDTO> status() {
        return Result.success(segmentIndexManager.status());
    }

    @RequireAuth()
    @PostMapping("/retry")
    public Result<Boolean> retry() {
        boolean accepted = segmentIndexManager.retryCreate();
        if (!accepted) {
            return Result.error("Retry conditions not met: index status must be NOT_READY and active embedding configured");
        }
        return Result.success(true);
    }

    @RequireAuth()
    @PostMapping("/rebuild/confirm")
    public Result<Boolean> confirmRebuild(@RequestBody IndexRebuildConfirmRequest request) {
        if (request.getTaskId() == null || request.getTaskId().isBlank()) {
            return Result.error("taskId is required");
        }
        boolean accepted = segmentIndexManager.confirmRebuild(request.getTaskId());
        if (!accepted) {
            return Result.error("Rebuild confirm failed: task not found, or another operation is in progress");
        }
        return Result.success(true);
    }

    @RequireAuth()
    @PostMapping("/rebuild/prepare")
    public Result<String> prepareRebuild() {
        try {
            String taskId = segmentIndexManager.prepareRebuild();
            return Result.success(taskId);
        } catch (IllegalStateException e) {
            return Result.error(e.getMessage());
        }
    }
}
