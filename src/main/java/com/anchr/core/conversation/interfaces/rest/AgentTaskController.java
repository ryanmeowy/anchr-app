package com.anchr.core.conversation.interfaces.rest;

import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.conversation.application.AgentTaskQueryService;
import com.anchr.core.conversation.interfaces.rest.dto.AgentTaskDTO;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1/agent/tasks")
@RequiredArgsConstructor
public class AgentTaskController {
    private final AgentTaskQueryService queryService;
    @GetMapping("/{taskId}")
    @RequireAuth(roles={"ADMIN","USER"})
    public Result<AgentTaskDTO> get(@PathVariable @NotBlank String taskId) {
        return Result.success(queryService.get(taskId));
    }

    @PostMapping("/{taskId}/cancel")
    @RequireAuth(roles={"ADMIN","USER"})
    public Result<AgentTaskDTO> cancel(@PathVariable @NotBlank String taskId) {
        return Result.success(queryService.cancel(taskId));
    }
}
