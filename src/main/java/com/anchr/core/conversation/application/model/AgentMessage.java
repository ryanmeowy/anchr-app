package com.anchr.core.conversation.application.model;

import java.util.List;

public record AgentMessage(String role,
                           String content,
                           String toolCallId,
                           String toolName,
                           List<AgentToolCall> toolCalls,
                           String reasoningContent) {

    public AgentMessage(String role, String content, String toolCallId, String toolName,
                        List<AgentToolCall> toolCalls) {
        this(role, content, toolCallId, toolName, toolCalls, null);
    }

    public AgentMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static AgentMessage system(String content) {
        return new AgentMessage("system", content, null, null, List.of());
    }

    public static AgentMessage user(String content) {
        return new AgentMessage("user", content, null, null, List.of());
    }

    public static AgentMessage assistant(String content) {
        return new AgentMessage("assistant", content, null, null, List.of());
    }

    public static AgentMessage assistantToolCalls(String content, List<AgentToolCall> toolCalls) {
        return new AgentMessage("assistant", content, null, null, toolCalls);
    }

    public static AgentMessage assistantToolCalls(String content, String reasoningContent,
                                                  List<AgentToolCall> toolCalls) {
        return new AgentMessage("assistant", content, null, null, toolCalls, reasoningContent);
    }

    public static AgentMessage tool(String callId, String name, String content) {
        return new AgentMessage("tool", content, callId, name, List.of());
    }
}
