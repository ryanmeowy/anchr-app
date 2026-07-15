package com.anchr.core.conversation.domain.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One conversation turn snapshot.
 */
@Data
@NoArgsConstructor
public class ConversationTurn {

    private String turnId;
    private String sessionId;
    private String query;
    private String rewrittenQuery;
    private String answer;
    private String kbScopeJson;
    private String assetScopeJson;
    private String answerMode;
    private String answerStatus;
    private String answerFallbackReason;
    private String intentType;
    private Double intentConfidence;
    private String intentReason;
    private String intentSource;
    private boolean intentFallback;
    private String citationsJson;
    private String resultCardsJson;
    private String retrievalTraceJson;
    private long createdAt;
}
