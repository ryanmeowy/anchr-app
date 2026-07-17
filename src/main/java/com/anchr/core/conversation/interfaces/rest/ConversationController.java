package com.anchr.core.conversation.interfaces.rest;

import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.conversation.application.ConversationService;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationCreateRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationRenameRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionListDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnListDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationCapabilitiesDTO;
import com.anchr.core.conversation.config.AgentProperties;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Conversation APIs.
 */
@RestController
@Validated
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final AgentProperties agentProperties;

    @GetMapping("/capabilities")
    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    public Result<ConversationCapabilitiesDTO> capabilities() {
        return Result.success(new ConversationCapabilitiesDTO(agentProperties.isEnabled(),
                agentProperties.getWorkflowVersion(), agentProperties.getSummaryMaxDocuments()));
    }

    @PostMapping
    @RequireAuth(roles = {"ADMIN", "USER"})
    public Result<ConversationSessionDTO> createSession(@Valid @RequestBody ConversationCreateRequestDTO request) {
        return Result.success(conversationService.createSession(request));
    }
    
    @GetMapping
    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    public Result<ConversationSessionListDTO> listSessions(
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit,
            @RequestParam(required = false) String cursor) {
        return Result.success(conversationService.listSessions(limit, cursor));
    }
    
    @GetMapping("/{sessionId}")
    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    public Result<ConversationSessionDTO> getSession(@PathVariable @NotBlank String sessionId) {
        return Result.success(conversationService.getSession(sessionId));
    }
    
    @PatchMapping("/{sessionId}")
    @RequireAuth(roles = {"ADMIN", "USER"})
    public Result<ConversationSessionDTO> renameSession(
            @PathVariable @NotBlank String sessionId,
            @Valid @RequestBody ConversationRenameRequestDTO request) {
        return Result.success(conversationService.renameSession(sessionId, request));
    }
    
    @DeleteMapping("/{sessionId}")
    @RequireAuth
    public Result<Void> deleteSession(@PathVariable @NotBlank String sessionId) {
        conversationService.deleteSession(sessionId);
        return Result.success();
    }
    
    @PostMapping("/{sessionId}/messages")
    @RequireAuth(roles = {"ADMIN", "USER"})
    public Result<ConversationMessageResponseDTO> createMessage(
            @PathVariable @NotBlank String sessionId,
            @Valid @RequestBody ConversationMessageRequestDTO request) {
        return Result.success(conversationService.createMessage(sessionId, request));
    }

    @PostMapping(value = "/{sessionId}/messages/stream", produces = "text/event-stream;charset=UTF-8")
    @RequireAuth(roles = {"ADMIN", "USER"})
    public SseEmitter streamMessage(
            @PathVariable @NotBlank String sessionId,
            @Valid @RequestBody ConversationMessageRequestDTO request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-store, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        return conversationService.streamMessage(sessionId, request);
    }
    
    @GetMapping("/{sessionId}/messages")
    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    public Result<ConversationTurnListDTO> listMessages(
            @PathVariable @NotBlank String sessionId,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @RequestParam(required = false) String beforeTurnId) {
        return Result.success(conversationService.listMessages(sessionId, limit, beforeTurnId));
    }
}
