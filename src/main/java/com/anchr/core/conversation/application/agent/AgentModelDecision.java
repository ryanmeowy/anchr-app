package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AgentToolCall;

import java.util.List;

sealed interface AgentModelDecision
        permits AgentModelDecision.ToolCalls, AgentModelDecision.FinalAnswer, AgentModelDecision.ProtocolError {
    record ToolCalls(List<AgentToolCall> calls) implements AgentModelDecision {
        public ToolCalls { calls = List.copyOf(calls); }
    }
    record FinalAnswer(AgentFinalAnswer answer) implements AgentModelDecision {}
    record ProtocolError(String code) implements AgentModelDecision {}
}
