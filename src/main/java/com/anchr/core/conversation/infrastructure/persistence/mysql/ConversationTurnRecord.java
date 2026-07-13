package com.anchr.core.conversation.infrastructure.persistence.mysql;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationTurnRecord {
    private String turnId;
    private String sessionId;
    private String role;
    private String query;
    private String rewrittenQuery;
    private String answer;
    private String kbScope;
    private String assetScope;
    private String answerMode;
    private String answerStatus;
    private String answerFallbackReason;
    private String citations;
    private String resultCards;
    private String retrievalTrace;
    private LocalDateTime createdAt;
}
