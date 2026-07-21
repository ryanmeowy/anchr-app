package com.anchr.core.conversation.domain.model;

/**
 * Stable keyset position for conversation history pagination.
 */
public record ConversationTurnPosition(String turnId, long createdAt) {
}
