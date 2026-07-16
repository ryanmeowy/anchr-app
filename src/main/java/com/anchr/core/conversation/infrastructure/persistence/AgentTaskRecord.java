package com.anchr.core.conversation.infrastructure.persistence;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AgentTaskRecord {
    private String taskId; private String runId; private String turnId; private String sessionId; private String userId;
    private String taskType; private String status; private int progress; private String currentStage;
    private String requestJson; private String answer; private String citationsJson; private int attemptCount;
    private LocalDateTime nextRetryAt; private String leaseOwner; private LocalDateTime leaseUntil;
    private String errorCode; private String errorMessage; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    private LocalDateTime startedAt; private LocalDateTime finishedAt;
}
