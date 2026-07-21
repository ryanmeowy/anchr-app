package com.anchr.core.conversation.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentRunRecord {
    private String runId;
    private String sessionId;
    private String turnId;
    private String workflowVersion;
    private String status;
    private String currentStep;
    private int stepCount;
    private int toolCallCount;
    private int promptTokens;
    private int completionTokens;
    private long latencyMs;
    private String fallbackReason;
    private String errorCode;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
