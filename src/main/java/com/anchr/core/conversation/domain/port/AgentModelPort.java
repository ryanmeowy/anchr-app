package com.anchr.core.conversation.domain.port;

import com.anchr.core.conversation.application.model.AgentModelRequest;
import com.anchr.core.conversation.application.model.AgentModelResponse;

public interface AgentModelPort {
    AgentModelResponse respond(AgentModelRequest request);
}
