package com.anchr.core.conversation.application.agent;

enum AgentWorkflowPhase {
    PLANNING,
    TOOL_EXECUTION,
    EVIDENCE_VALIDATION,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELLED;

    boolean canTransitionTo(AgentWorkflowPhase next) {
        if (next == this) return !terminal();
        if (terminal()) return false;
        if (next == FAILED || next == CANCELLED) return true;
        return switch (this) {
            case PLANNING -> next == TOOL_EXECUTION
                    || next == EVIDENCE_VALIDATION
                    || next == FINALIZING
                    || next == COMPLETED;
            case TOOL_EXECUTION -> next == PLANNING
                    || next == TOOL_EXECUTION
                    || next == EVIDENCE_VALIDATION
                    || next == FINALIZING
                    || next == COMPLETED;
            case EVIDENCE_VALIDATION -> next == PLANNING
                    || next == FINALIZING
                    || next == COMPLETED;
            case FINALIZING -> next == COMPLETED;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }

    boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
