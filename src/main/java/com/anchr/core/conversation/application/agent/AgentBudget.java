package com.anchr.core.conversation.application.agent;

import java.time.Duration;

import static com.anchr.core.conversation.application.constant.ConversationConstant.DEFAULT_TIMEOUT;

public record AgentBudget(int maxSteps, int maxToolCalls, long deadlineEpochMs) {

    public boolean exhausted(int steps, int toolCalls, long now) {
        return steps >= maxSteps || toolCalls >= maxToolCalls || remainingMillis(now) <= 0;
    }

    public long remainingMillis(long now) {
        return Math.max(0L, deadlineEpochMs - now);
    }

    public Duration boundedTimeout(Duration configured, long now) {
        long configuredMs = configured == null ? DEFAULT_TIMEOUT.toMillis() : configured.toMillis();
        return Duration.ofMillis(Math.max(1L, Math.min(configuredMs, remainingMillis(now))));
    }
}
