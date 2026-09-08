package com.anchr.core.conversation.application.model;

public enum ConversationIntentType {
    CHAT,
    KB_QUERY,
    /** Historical turns only; new routing defaults non-CHAT requests to KB_QUERY. */
    OTHER
}
