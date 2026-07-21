package com.anchr.core.conversation.application.model;

import java.util.List;

public record AgentModelRequest(List<AgentMessage> messages,
                                List<AgentToolDefinition> tools,
                                AgentModelOptions options) {
}
