package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AgentMessage;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
public class AgentRunState {
    private final AgentRunRequest runRequest;
    private final AgentBudget budget;
    private final long startedAt;
    private final List<AgentMessage> messages = new ArrayList<>();
    private final Map<String, ConversationRetrievalCandidate> evidence = new LinkedHashMap<>();
    private final java.util.Set<String> executedToolCalls = new java.util.HashSet<>();
    private int stepCount;
    private int toolCallCount;
    private int promptTokens;
    private int completionTokens;
    private int consecutiveProtocolErrors;
    private int answerValidationErrors;
    private int traceOrder;
    @Setter
    private AgentStepType currentStep = AgentStepType.MODEL_DECISION;

    public AgentRunState(AgentRunRequest runRequest, AgentBudget budget, long startedAt) {
        this.runRequest = runRequest;
        this.budget = budget;
        this.startedAt = startedAt;
    }

    public int nextStep() {
        return ++stepCount;
    }

    public int nextToolCall() {
        return ++toolCallCount;
    }

    public int nextProtocolError() {
        return ++consecutiveProtocolErrors;
    }

    public void resetProtocolErrors() {
        consecutiveProtocolErrors = 0;
    }

    public int nextAnswerValidationError() {
        return ++answerValidationErrors;
    }

    public int nextTraceOrder() { return ++traceOrder; }

    public void addUsage(int prompt, int completion) {
        promptTokens += Math.max(0, prompt);
        completionTokens += Math.max(0, completion);
    }

    public void registerEvidence(List<ConversationRetrievalCandidate> candidates) {
        if (candidates == null) return;
        for (ConversationRetrievalCandidate candidate : candidates) {
            if (candidate != null && candidate.getSegmentId() != null) {
                evidence.putIfAbsent(candidate.getSegmentId(), candidate);
            }
        }
    }

    public boolean markToolCall(String id, String name, String arguments) {
        String key = id != null && !id.isBlank() ? id : String.valueOf(name) + "\n" + String.valueOf(arguments);
        return executedToolCalls.add(key);
    }
}
