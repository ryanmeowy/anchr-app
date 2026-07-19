package com.anchr.core.conversation.interfaces.rest.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AgentRunSummaryDTO implements Serializable {
    private String runId;
    private String sessionId;
    private String turnId;
    private String status;
    private String currentStep;
    private long startedAt;
    private Long finishedAt;
}
