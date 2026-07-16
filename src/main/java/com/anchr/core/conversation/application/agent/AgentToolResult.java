package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;

import java.util.List;

public record AgentToolResult(boolean success,
                              String content,
                              List<ConversationRetrievalCandidate> evidence,
                              AgentDeferredTask deferredTask,
                              AgentFinalAnswer finalAnswer,
                              String errorCode) {

    public AgentToolResult {
        content = content == null ? "{}" : content;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static AgentToolResult success(String content, List<ConversationRetrievalCandidate> evidence) {
        return new AgentToolResult(true, content, evidence, null, null, null);
    }

    public static AgentToolResult deferred(String content, AgentDeferredTask task) {
        return new AgentToolResult(true, content, List.of(), task, null, null);
    }

    public static AgentToolResult finalAnswer(AgentFinalAnswer answer) {
        return new AgentToolResult(true, "{}", List.of(), null, answer, null);
    }

    public static AgentToolResult failure(String code, String content) {
        return new AgentToolResult(false, content, List.of(), null, null, code);
    }
}
