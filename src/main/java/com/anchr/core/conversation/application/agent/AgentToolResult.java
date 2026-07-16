package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;

import java.util.List;
import java.util.Map;

public record AgentToolResult(boolean success,
                              String content,
                              List<ConversationRetrievalCandidate> evidence,
                              AgentDeferredTask deferredTask,
                              AgentFinalAnswer finalAnswer,
                              String errorCode,
                              Map<String, Object> traceDetails) {

    public AgentToolResult {
        content = content == null ? "{}" : content;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        traceDetails = traceDetails == null ? Map.of() : Map.copyOf(traceDetails);
    }

    public static AgentToolResult success(String content, List<ConversationRetrievalCandidate> evidence) {
        return success(content, evidence, Map.of("evidenceCount", evidence == null ? 0 : evidence.size()));
    }

    public static AgentToolResult success(String content, List<ConversationRetrievalCandidate> evidence,
                                          Map<String, Object> traceDetails) {
        return new AgentToolResult(true, content, evidence, null, null, null, traceDetails);
    }

    public static AgentToolResult deferred(String content, AgentDeferredTask task) {
        return deferred(content, task, Map.of());
    }

    public static AgentToolResult deferred(String content, AgentDeferredTask task, Map<String, Object> traceDetails) {
        return new AgentToolResult(true, content, List.of(), task, null, null, traceDetails);
    }

    public static AgentToolResult finalAnswer(AgentFinalAnswer answer) {
        return finalAnswer(answer, Map.of());
    }

    public static AgentToolResult finalAnswer(AgentFinalAnswer answer, Map<String, Object> traceDetails) {
        return new AgentToolResult(true, "{}", List.of(), null, answer, null, traceDetails);
    }

    public static AgentToolResult failure(String code, String content) {
        return new AgentToolResult(false, content, List.of(), null, null, code, Map.of());
    }
}
