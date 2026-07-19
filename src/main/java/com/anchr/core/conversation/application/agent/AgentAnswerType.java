package com.anchr.core.conversation.application.agent;

/**
 * Declares whether a final Agent answer must be grounded in evidence from the current run.
 */
public enum AgentAnswerType {
    CHAT,
    CLARIFICATION,
    KNOWLEDGE,
    NO_EVIDENCE
}
