package com.anchr.core.conversation.domain.model;

import lombok.Data;

@Data
public class AgentRun {
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
    private long startedAt;
    private Long finishedAt;
}
