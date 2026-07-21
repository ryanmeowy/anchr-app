package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;

public record AgentRunRequest(String runId,
                              String turnId,
                              String sessionId,
                              String userId,
                              ConversationMessageRequestDTO request) {
}
