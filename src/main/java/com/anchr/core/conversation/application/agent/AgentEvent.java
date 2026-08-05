package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AgentModelResponse;
import com.anchr.core.conversation.application.model.AgentTokenUsage;
import com.anchr.core.conversation.application.model.AgentToolCall;

sealed interface AgentEvent permits AgentEvent.RunStarted, AgentEvent.ModelCompleted,
        AgentEvent.ModelFailed, AgentEvent.ToolCompleted, AgentEvent.ToolFailed,
        AgentEvent.AnswerAccepted, AgentEvent.AnswerRejected, AgentEvent.AnswerVerificationFailed,
        AgentEvent.FinalizerModelCompleted, AgentEvent.FinalizerModelFailed,
        AgentEvent.PresentationCompleted, AgentEvent.PresentationFailed,
        AgentEvent.CancellationRequested {

    long occurredAt();

    record RunStarted(long occurredAt) implements AgentEvent {}
    record ModelCompleted(AgentModelResponse response, AgentModelDecision decision,
                          long durationMs, long occurredAt) implements AgentEvent {}
    record ModelFailed(RuntimeException cause, long durationMs, long occurredAt) implements AgentEvent {}
    record ToolCompleted(AgentToolCall call, AgentToolResult result, String modelMessage,
                         int attempt, long durationMs, long occurredAt) implements AgentEvent {}
    record ToolFailed(AgentToolCall call, RuntimeException cause, int attempt,
                      long durationMs, long occurredAt) implements AgentEvent {}
    record AnswerAccepted(VerifiedAgentAnswer answer, long occurredAt) implements AgentEvent {}
    record AnswerRejected(String code, String message, String fallbackReason,
                          String validationToolCallId, String validationToolName,
                          long occurredAt) implements AgentEvent {}
    record AnswerVerificationFailed(RuntimeException cause, long occurredAt) implements AgentEvent {}
    record FinalizerModelCompleted(AgentAnswerValidationOutcome validation,
                                   AgentTokenUsage usage, boolean hasContent,
                                   long durationMs, long occurredAt) implements AgentEvent {}
    record FinalizerModelFailed(RuntimeException cause, long durationMs,
                                long occurredAt) implements AgentEvent {}
    record PresentationCompleted(PresentedAgentAnswer answer, AgentTokenUsage usage,
                                 boolean modelAttempted, boolean modelSucceeded,
                                 long firstTokenMs, long durationMs,
                                 long occurredAt) implements AgentEvent {}
    record PresentationFailed(PresentedAgentAnswer fallback, RuntimeException cause,
                              boolean modelAttempted, long firstTokenMs,
                              long durationMs, long occurredAt) implements AgentEvent {}
    record CancellationRequested(long occurredAt) implements AgentEvent {}
}
