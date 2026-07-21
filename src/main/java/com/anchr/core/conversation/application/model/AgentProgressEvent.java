package com.anchr.core.conversation.application.model;

import java.util.Map;

public record AgentProgressEvent(String runId,
                                 String stage,
                                 String message,
                                 int attempt,
                                 Map<String, Object> details) {
}
