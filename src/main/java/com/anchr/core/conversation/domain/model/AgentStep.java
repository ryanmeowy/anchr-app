package com.anchr.core.conversation.domain.model;

import lombok.Data;

@Data
public class AgentStep {
    private String stepId;
    private String runId;
    private int stepOrder;
    private String stepType;
    private int attempt;
    private String status;
    private String decisionCode;
    private String inputSummaryJson;
    private String outputSummaryJson;
    private int promptTokens;
    private int completionTokens;
    private long latencyMs;
    private String errorCode;
    private long createdAt;
}
