package com.anchr.core.conversation.application.agent;

public enum AgentStepType {
    MODEL_DECISION,
    TOOL_CALL,
    TOOL_RESULT,
    TASK_STAGE,
    FINAL_ANSWER,
    FAILED
}
