package com.anchr.core.conversation.domain.model;

/**
 * Stable position used to continue a conversation-session keyset page.
 */
public record ConversationSessionPosition(String sessionId, long updatedAt) {
}
