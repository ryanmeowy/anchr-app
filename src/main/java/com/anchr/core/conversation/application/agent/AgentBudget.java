package com.anchr.core.conversation.application.agent;

import java.time.Duration;

public record AgentBudget(int maxSteps, int maxToolCalls, long deadlineEpochMs) {

    public boolean exhausted(int steps, int toolCalls) {
        return steps >= maxSteps || toolCalls >= maxToolCalls || remainingMillis() <= 0;
    }

    public long remainingMillis() {
        return Math.max(0L, deadlineEpochMs - System.currentTimeMillis());
    }

    public Duration boundedTimeout(Duration configured) {
        long configuredMs = configured == null ? 30_000L : configured.toMillis();
        return Duration.ofMillis(Math.max(1L, Math.min(configuredMs, remainingMillis())));
    }
}
