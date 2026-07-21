package com.anchr.core.conversation.application.agent;

public enum AgentRunStatus {
    RUNNING,
    AWAITING_TURN,
    WAITING_TASK,
    COMPLETED,
    CANCELLED,
    FAILED,
    DEGRADED,
    FALLBACK
}
