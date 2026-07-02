package com.anchr.core.conversation.domain.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Conversation session aggregate root.
 */
@Data
@NoArgsConstructor
public class ConversationSession {

    private String sessionId;
    private String userId;
    private String title;
    private ConversationSessionStatus status;
    private long createdAt;
    private long updatedAt;
    private long expiresAt;
    private List<String> kbScope;
    private List<String> assetScope;

    public static ConversationSession createActive(String sessionId, String userId, String title, long now, long expiresAt) {
        ConversationSession session = new ConversationSession();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setTitle(title);
        session.setStatus(ConversationSessionStatus.ACTIVE);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setExpiresAt(expiresAt);
        return session;
    }

    public void touch(long now, long expiresAt) {
        this.updatedAt = now;
        this.expiresAt = expiresAt;
    }
}
