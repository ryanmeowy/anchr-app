package com.anchr.core.conversation.application.model;

public record AgentTokenUsage(int promptTokens, int completionTokens) {

    public static final AgentTokenUsage EMPTY = new AgentTokenUsage(0, 0);

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
