package com.anchr.core.conversation.interfaces.rest;

import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.conversation.application.AgentTaskQueryService;
import com.anchr.core.conversation.application.agent.AgentTaskStreamService;
import com.anchr.core.conversation.interfaces.rest.dto.AgentTaskDTO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Validated
@RequestMapping("/api/v1/agent/tasks")
@RequiredArgsConstructor
public class AgentTaskController {
    private final AgentTaskQueryService queryService;
    private final AgentTaskStreamService taskStreamService;
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

    @GetMapping(value = "/{taskId}/stream", produces = "text/event-stream;charset=UTF-8")
    @RequireAuth(roles={"ADMIN","USER"})
    public SseEmitter stream(@PathVariable @NotBlank String taskId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-store, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        return taskStreamService.subscribe(queryService.get(taskId));
    }
}
