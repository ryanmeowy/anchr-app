package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.model.ConversationExecutionResult;

public interface AgentWorkflow {
    ConversationExecutionResult execute(AgentRunRequest request, ConversationProgressListener listener);
}
