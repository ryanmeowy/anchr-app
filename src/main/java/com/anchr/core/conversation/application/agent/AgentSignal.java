package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AgentTokenUsage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

sealed interface AgentSignal permits AgentSignal.RunStarted, AgentSignal.Progress,
        AgentSignal.Trace, AgentSignal.ProtocolError, AgentSignal.NoEvidenceDeclared,
        AgentSignal.AnswerValidationRejected, AgentSignal.EffectFailure, AgentSignal.Terminal {

    record RunStarted() implements AgentSignal {}
    record Progress(String stage, String message, int stepCount,
                    Map<String, Object> details) implements AgentSignal {
        public Progress { details = immutableMap(details); }
    }
    record Trace(int stepOrder, AgentStepType type, int attempt, String decision,
                 Map<String, Object> inputSummary, Map<String, Object> outputSummary,
                 AgentTokenUsage usage, long latencyMs, String errorCode) implements AgentSignal {
        public Trace {
            inputSummary = immutableMap(inputSummary);
            outputSummary = immutableMap(outputSummary);
            usage = usage == null ? AgentTokenUsage.EMPTY : usage;
        }
    }
    record ProtocolError(String code, String outcome, int consecutiveErrors,
                         int stepCount, int toolCallCount) implements AgentSignal {}
    record NoEvidenceDeclared() implements AgentSignal {}
    record AnswerValidationRejected(int attempt, String tool, String callId, String code,
                                    String fallbackReason, String message) implements AgentSignal {}
    record EffectFailure(String phase, RuntimeException cause) implements AgentSignal {}
    record Terminal(AgentRunStatus status, String fallbackReason) implements AgentSignal {}

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source == null ? Map.of() : source));
    }
}
