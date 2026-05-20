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
    private ConversationRole role;
    private String query;
    private String rewrittenQuery;
    private String answer;
    private String citationsJson;
    private String resultCardsJson;
    private String retrievalTraceJson;
    private long createdAt;
}
