package com.anchr.core.conversation.application.model;

import java.time.Duration;

public record AgentModelOptions(Double temperature,
                                Integer maxTokens,
                                Duration timeout,
                                String toolCallMode,
                                boolean toolsEnabled) {
}
