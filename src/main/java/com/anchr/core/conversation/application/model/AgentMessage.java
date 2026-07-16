package com.anchr.core.conversation.application.model;

public record AgentMessage(String role,
                           String content,
                           String toolCallId,
                           String toolName,
                           java.util.List<AgentToolCall> toolCalls) {

    public AgentMessage {
        toolCalls = toolCalls == null ? java.util.List.of() : java.util.List.copyOf(toolCalls);
    }

    public static AgentMessage system(String content) {
        return new AgentMessage("system", content, null, null, java.util.List.of());
    }

    public static AgentMessage user(String content) {
        return new AgentMessage("user", content, null, null, java.util.List.of());
    }

    public static AgentMessage assistant(String content) {
        return new AgentMessage("assistant", content, null, null, java.util.List.of());
    }

    public static AgentMessage assistantToolCalls(String content, java.util.List<AgentToolCall> toolCalls) {
        return new AgentMessage("assistant", content, null, null, toolCalls);
    }

    public static AgentMessage tool(String callId, String name, String content) {
        return new AgentMessage("tool", content, callId, name, java.util.List.of());
    }
}
