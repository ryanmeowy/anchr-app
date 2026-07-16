package com.anchr.core.conversation.application.agent;

import java.util.List;

public record AgentExecutionContext(String runId,
                                    String turnId,
                                    String sessionId,
                                    String userId,
                                    List<String> kbIds,
                                    List<String> assetIds,
                                    AgentBudget budget) {

    public AgentExecutionContext {
        kbIds = kbIds == null ? List.of() : List.copyOf(kbIds);
        assetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
    }
}
