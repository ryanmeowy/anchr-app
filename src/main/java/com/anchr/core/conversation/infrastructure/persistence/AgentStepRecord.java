package com.anchr.core.conversation.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentStepRecord {
    private String stepId;
    private String runId;
    private int stepOrder;
    private String stepType;
    private int attempt;
    private String status;
    private String decisionCode;
    private String inputSummary;
    private String outputSummary;
    private int promptTokens;
    private int completionTokens;
    private long latencyMs;
    private String errorCode;
    private LocalDateTime createdAt;
}
