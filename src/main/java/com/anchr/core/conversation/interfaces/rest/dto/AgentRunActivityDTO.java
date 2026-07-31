package com.anchr.core.conversation.interfaces.rest.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class AgentRunActivityDTO implements Serializable {
    private String runId;
    private String sessionId;
    private String turnId;
    private String status;
    private String currentStep;
    private int stepCount;
    private int toolCallCount;
    private int promptTokens;
    private int completionTokens;
    private long latencyMs;
    private String fallbackReason;
    private long startedAt;
    private Long finishedAt;
    private List<StepDTO> steps = new ArrayList<>();

    @Data
    public static class StepDTO implements Serializable {
        private int stepOrder;
        private String type;
        private String toolName;
        private String callId;
        private String taskStage;
        private String taskType;
        private String answerType;
        private String model;
        private String decision;
        private String status;
        private int attempt;
        private Integer progress;
        private Integer messageCount;
        private Integer plannedToolCallCount;
        private Integer evidenceCount;
        private Integer documentCount;
        private Integer segmentCount;
        private Integer batchCount;
        private Integer citationCount;
        private Boolean hasMore;
        private int promptTokens;
        private int completionTokens;
        private Integer modelCallCount;
        private Long modelLatencyMs;
        private Long firstTokenMs;
        private Boolean streaming;
        private long durationMs;
        private long createdAt;
        private String errorCode;
    }
}
