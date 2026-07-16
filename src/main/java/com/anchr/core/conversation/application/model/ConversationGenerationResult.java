package com.anchr.core.conversation.application.model;

public record ConversationGenerationResult(
        String content,
        int promptTokens,
        int completionTokens
) {
    public ConversationGenerationResult {
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
    }
}
