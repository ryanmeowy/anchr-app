package com.smart.vision.core.conversation.interfaces.rest;

import com.smart.vision.core.auth.RequireAuth;
import com.smart.vision.core.common.model.Result;
import com.smart.vision.core.conversation.application.ConversationService;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationCreateRequestDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationRenameRequestDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationSessionDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationSessionListDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationTurnListDTO;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Conversation APIs.
 */
@RestController
@Validated
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationApiController {

    private static final String USER_KEY_HEADER = "X-User-Key";

    private final ConversationService conversationService;

    @PostMapping
    public Result<ConversationSessionDTO> createSession(
            @RequestHeader(value = USER_KEY_HEADER, required = false) String userKey,
            @Valid @RequestBody ConversationCreateRequestDTO request) {
        return Result.success(conversationService.createSession(resolveUserId(userKey), request));
    }
    
    @GetMapping
    public Result<ConversationSessionListDTO> listSessions(
            @RequestHeader(value = USER_KEY_HEADER, required = false) String userKey,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit,
            @RequestParam(required = false) String cursor) {
        return Result.success(conversationService.listSessions(resolveUserId(userKey), limit, cursor));
    }
    
    @GetMapping("/{sessionId}")
    public Result<ConversationSessionDTO> getSession(
            @RequestHeader(value = USER_KEY_HEADER, required = false) String userKey,
            @PathVariable @NotBlank String sessionId) {
        return Result.success(conversationService.getSession(resolveUserId(userKey), sessionId));
    }
    
    @PatchMapping("/{sessionId}")
    public Result<ConversationSessionDTO> renameSession(
            @RequestHeader(value = USER_KEY_HEADER, required = false) String userKey,
            @PathVariable @NotBlank String sessionId,
            @Valid @RequestBody ConversationRenameRequestDTO request) {
        return Result.success(conversationService.renameSession(resolveUserId(userKey), sessionId, request));
    }
    
    @DeleteMapping("/{sessionId}")
    public Result<Void> deleteSession(
            @RequestHeader(value = USER_KEY_HEADER, required = false) String userKey,
            @PathVariable @NotBlank String sessionId) {
        conversationService.deleteSession(resolveUserId(userKey), sessionId);
        return Result.success();
    }
    
    @PostMapping("/{sessionId}/messages")
    public Result<ConversationMessageResponseDTO> createMessage(
            @RequestHeader(value = USER_KEY_HEADER, required = false) String userKey,
            @PathVariable @NotBlank String sessionId,
            @Valid @RequestBody ConversationMessageRequestDTO request) {
        return Result.success(conversationService.createMessage(resolveUserId(userKey), sessionId, request));
    }
    
    @GetMapping("/{sessionId}/messages")
    public Result<ConversationTurnListDTO> listMessages(
            @RequestHeader(value = USER_KEY_HEADER, required = false) String userKey,
            @PathVariable @NotBlank String sessionId,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @RequestParam(required = false) String beforeTurnId) {
        return Result.success(conversationService.listMessages(resolveUserId(userKey), sessionId, limit, beforeTurnId));
    }

    private String resolveUserId(String userKey) {
        if (userKey == null || userKey.isBlank()) {
            return "uk_default";
        }
        byte[] digest = sha256(userKey.trim());
        StringBuilder builder = new StringBuilder("uk_");
        for (int i = 0; i < 8; i++) {
            builder.append(String.format("%02x", digest[i]));
        }
        return builder.toString();
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve conversation user id.", e);
        }
    }
}
