package com.anchr.core.conversation.application.agent;

public enum AgentStepType {
    MODEL_DECISION,
    TOOL_CALL,
    TOOL_RESULT,
    FINAL_ANSWER,
    FAILED
}
