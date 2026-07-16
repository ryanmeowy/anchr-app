package com.anchr.core.conversation.application.model;

import java.util.List;

public record AgentModelResponse(String content,
                                 List<AgentToolCall> toolCalls,
                                 AgentTokenUsage usage,
                                 String model,
                                 String finishReason,
                                 String requestId) {

    public AgentModelResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? AgentTokenUsage.EMPTY : usage;
    }
}
