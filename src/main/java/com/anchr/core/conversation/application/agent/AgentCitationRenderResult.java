package com.anchr.core.conversation.application.agent;

import java.util.Map;

public record AgentCitationRenderResult(
        String answer,
        Map<String, AgentCitationReference> references
) {
}
