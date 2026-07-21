package com.anchr.core.conversation.interfaces.rest;

import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.conversation.application.AgentRunCancellationService;
import com.anchr.core.conversation.application.AgentRunActivityService;
import com.anchr.core.conversation.application.AgentRuntimeSnapshotService;
import com.anchr.core.conversation.interfaces.rest.dto.AgentRunActivityDTO;
import com.anchr.core.conversation.interfaces.rest.dto.AgentRuntimeSnapshotDTO;
import com.anchr.core.conversation.interfaces.rest.dto.AgentRunSummaryDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/agent/runs")
@RequiredArgsConstructor
public class AgentRunController {
    private final AgentRunCancellationService cancellationService;
    private final AgentRunActivityService activityService;
    private final AgentRuntimeSnapshotService runtimeSnapshotService;

    @GetMapping("/recoverable")
    @RequireAuth(roles={"ADMIN","USER", "GUEST"})
    public Result<List<AgentRunSummaryDTO>> recoverable(
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit) {
        return Result.success(activityService.listRecoverable(limit));
    }

    @GetMapping("/{runId}/activity")
    @RequireAuth(roles={"ADMIN","USER", "GUEST"})
    public Result<AgentRunActivityDTO> activity(@PathVariable @NotBlank String runId) {
        return Result.success(activityService.get(runId));
    }

    @GetMapping("/{runId}/runtime-snapshot")
    @RequireAuth(roles={"ADMIN","USER", "GUEST"})
    public Result<AgentRuntimeSnapshotDTO> runtimeSnapshot(
            @PathVariable @NotBlank String runId,
            @RequestParam(defaultValue = "0") @Min(0) long afterVersion) {
        // Preserve the same access and existence checks as the persisted Activity endpoint.
        activityService.verifyAccessible(runId);
        return Result.success(runtimeSnapshotService.get(runId, afterVersion));
    }

    @PostMapping("/{runId}/cancel")
    @RequireAuth(roles={"ADMIN","USER"})
    public Result<Boolean> cancel(@PathVariable @NotBlank String runId) {
        return Result.success(cancellationService.cancel(runId));
    }
}
