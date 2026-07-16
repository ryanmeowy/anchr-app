package com.anchr.core.conversation.domain.model;

import lombok.Data;

@Data
public class AgentTask {
    private String taskId;
    private String runId;
    private String turnId;
    private String sessionId;
    private String userId;
    private String taskType;
    private String status;
    private int progress;
    private String currentStage;
    private String requestJson;
    private String answer;
    private String citationsJson;
    private int attemptCount;
    private Long nextRetryAt;
    private String leaseOwner;
    private Long leaseUntil;
    private String errorCode;
    private String errorMessage;
    private long createdAt;
    private long updatedAt;
    private Long startedAt;
    private Long finishedAt;
}
