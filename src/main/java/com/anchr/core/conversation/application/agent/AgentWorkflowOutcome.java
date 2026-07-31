package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.ConversationExecutionResult;

sealed interface AgentWorkflowOutcome
        permits AgentWorkflowOutcome.ContinuePlanning,
        AgentWorkflowOutcome.AnswerSubmitted,
        AgentWorkflowOutcome.Terminal {

    record ContinuePlanning() implements AgentWorkflowOutcome {
        static final ContinuePlanning INSTANCE = new ContinuePlanning();
    }

    record AnswerSubmitted(UnverifiedAgentAnswer answer) implements AgentWorkflowOutcome {
    }

    record Terminal(ConversationExecutionResult result) implements AgentWorkflowOutcome {
    }

    static ContinuePlanning continuePlanning() {
        return ContinuePlanning.INSTANCE;
    }

    static AnswerSubmitted submitted(AgentFinalAnswer answer, String callId, String toolName) {
        return new AnswerSubmitted(new UnverifiedAgentAnswer(answer, callId, toolName));
    }

    static Terminal terminal(ConversationExecutionResult result) {
        return new Terminal(result);
    }
}
