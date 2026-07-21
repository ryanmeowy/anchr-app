package com.anchr.core.conversation.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class AgentToolExecutor {
    private final AgentToolRegistry registry;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public AgentToolResult execute(String name, String arguments, AgentExecutionContext context) {
        AgentTool<?> tool;
        try {
            tool = registry.require(name);
        } catch (IllegalArgumentException e) {
            return AgentToolResult.failure("UNKNOWN_TOOL", jsonError("UNKNOWN_TOOL", e.getMessage()));
        }
        try {
            Object input = objectMapper.readValue(arguments == null || arguments.isBlank() ? "{}" : arguments,
                    tool.inputType());
            Set<ConstraintViolation<Object>> violations = validator.validate(input);
            if (!violations.isEmpty()) {
                String detail = violations.stream().map(ConstraintViolation::getMessage)
                        .sorted().collect(Collectors.joining("; "));
                return AgentToolResult.failure("INVALID_ARGUMENTS", jsonError("INVALID_ARGUMENTS", detail));
            }
            return invoke(tool, input, context);
        } catch (JsonProcessingException e) {
            return AgentToolResult.failure("INVALID_ARGUMENTS", jsonError("INVALID_ARGUMENTS", e.getOriginalMessage()));
        } catch (AgentToolException e) {
            return AgentToolResult.failure(e.getCode(), jsonError(e.getCode(), e.getMessage()));
        } catch (SecurityException e) {
            return AgentToolResult.failure("PERMISSION_DENIED", jsonError("PERMISSION_DENIED", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return AgentToolResult.failure("INVALID_ARGUMENTS", jsonError("INVALID_ARGUMENTS", e.getMessage()));
        } catch (Exception e) {
            return AgentToolResult.failure("TOOL_EXECUTION_FAILED",
                    jsonError("TOOL_EXECUTION_FAILED", e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private <I> AgentToolResult invoke(AgentTool<?> source, Object input, AgentExecutionContext context) {
        return ((AgentTool<I>) source).execute((I) input, context);
    }

    private String jsonError(String code, String message) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "success", false, "errorCode", code,
                    "message", message == null ? code : message));
        } catch (Exception ignored) {
            return "{\"success\":false,\"errorCode\":\"" + code + "\"}";
        }
    }
}
