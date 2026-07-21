package com.anchr.core.conversation.application.model;

public record ConversationIntentResult(ConversationIntentType type,
                                       double confidence,
                                       String reason,
                                       ConversationIntentSource source,
                                       boolean fallbackUsed) {

    public boolean retrievalRequired() {
        return type == ConversationIntentType.KB_QUERY;
    }
}
