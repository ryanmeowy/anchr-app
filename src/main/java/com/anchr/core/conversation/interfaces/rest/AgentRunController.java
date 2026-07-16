package com.anchr.core.conversation.interfaces.rest;

import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.conversation.application.AgentRunCancellationService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/agent/runs")
@RequiredArgsConstructor
public class AgentRunController {
    private final AgentRunCancellationService cancellationService;

    @PostMapping("/{runId}/cancel")
    @RequireAuth(roles={"ADMIN","USER"})
    public Result<Boolean> cancel(@PathVariable @NotBlank String runId) {
        return Result.success(cancellationService.cancel(runId));
    }
}
