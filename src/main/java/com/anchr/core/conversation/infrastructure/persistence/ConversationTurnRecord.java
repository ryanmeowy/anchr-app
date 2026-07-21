package com.anchr.core.conversation.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationTurnRecord {
    private String turnId;
    private String sessionId;
    private String query;
    private String rewrittenQuery;
    private String answer;
    private String kbScope;
    private String assetScope;
    private String answerMode;
    private String answerStatus;
    private String answerFallbackReason;
    private String intentType;
    private Double intentConfidence;
    private String intentReason;
    private String intentSource;
    private boolean intentFallback;
    private String citations;
    private String resultCards;
    private String retrievalTrace;
    private String agentRunId;
    private String workflowVersion;
    private String executionMode;
    private String agentTaskId;
    private LocalDateTime createdAt;
}
