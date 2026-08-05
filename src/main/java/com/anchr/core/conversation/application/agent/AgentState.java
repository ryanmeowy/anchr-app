package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AgentMessage;
import com.anchr.core.conversation.application.model.AgentToolCall;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.common.model.BboxInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable snapshot of all state owned by the Agent transition engine. */
public record AgentState(
        AgentRunRequest runRequest,
        AgentBudget budget,
        long startedAt,
        AgentRuntimeSettings runtimeConfig,
        boolean streamingSupported,
        List<AgentMessage> messages,
        Map<String, ConversationRetrievalCandidate> evidence,
        Set<String> executedToolCalls,
        Map<String, Integer> toolExecutions,
        List<AgentToolCall> pendingToolCalls,
        int stepCount,
        int toolCallCount,
        int promptTokens,
        int completionTokens,
        int consecutiveProtocolErrors,
        int answerValidationErrors,
        int finalizerAttempts,
        int traceOrder,
        AgentWorkflowPhase phase,
        AgentStepType currentStep,
        String finalizerTrigger,
        String lastFinalizerInvalid
) {
    public AgentState {
        messages = List.copyOf(messages == null ? List.of() : messages);
        evidence = immutableEvidence(evidence);
        executedToolCalls = Collections.unmodifiableSet(
                new LinkedHashSet<>(executedToolCalls == null ? Set.of() : executedToolCalls));
        toolExecutions = Collections.unmodifiableMap(
                new LinkedHashMap<>(toolExecutions == null ? Map.of() : toolExecutions));
        pendingToolCalls = List.copyOf(pendingToolCalls == null ? List.of() : pendingToolCalls);
    }

    static AgentState initial(AgentRunRequest request, AgentBudget budget, long startedAt,
                              AgentRuntimeSettings settings, boolean streamingSupported,
                              List<AgentMessage> messages) {
        return new AgentState(request, budget, startedAt, settings, streamingSupported, messages,
                Map.of(), Set.of(), Map.of(), List.of(), 0, 0, 0, 0, 0, 0, 0, 0,
                AgentWorkflowPhase.PLANNING, AgentStepType.MODEL_DECISION, null, null);
    }

    AgentState appendMessage(AgentMessage message) {
        List<AgentMessage> next = new ArrayList<>(messages);
        next.add(message);
        return copy(next, evidence, executedToolCalls, toolExecutions, pendingToolCalls,
                stepCount, toolCallCount, promptTokens, completionTokens,
                consecutiveProtocolErrors, answerValidationErrors, finalizerAttempts, traceOrder,
                phase, currentStep, finalizerTrigger, lastFinalizerInvalid);
    }

    AgentState registerEvidence(List<ConversationRetrievalCandidate> candidates) {
        Map<String, ConversationRetrievalCandidate> next = new LinkedHashMap<>(evidence);
        if (candidates != null) {
            for (ConversationRetrievalCandidate candidate : candidates) {
                if (candidate != null && candidate.getSegmentId() != null
                        && !candidate.getSegmentId().isBlank() && candidate.isCitableEvidence()) {
                    next.putIfAbsent(candidate.getSegmentId(), copyCandidate(candidate));
                }
            }
        }
        return copy(messages, next, executedToolCalls, toolExecutions, pendingToolCalls,
                stepCount, toolCallCount, promptTokens, completionTokens,
                consecutiveProtocolErrors, answerValidationErrors, finalizerAttempts, traceOrder,
                phase, currentStep, finalizerTrigger, lastFinalizerInvalid);
    }

    AgentState withPlanningCall() {
        return copy(messages, evidence, executedToolCalls, toolExecutions, pendingToolCalls,
                stepCount + 1, toolCallCount, promptTokens, completionTokens,
                consecutiveProtocolErrors, answerValidationErrors, finalizerAttempts, traceOrder,
                AgentWorkflowPhase.PLANNING, AgentStepType.MODEL_DECISION, finalizerTrigger, lastFinalizerInvalid);
    }

    AgentState withModelResult(int prompt, int completion, int newTraceOrder) {
        return copy(messages, evidence, executedToolCalls, toolExecutions, pendingToolCalls,
                stepCount, toolCallCount, promptTokens + Math.max(0, prompt),
                completionTokens + Math.max(0, completion), consecutiveProtocolErrors,
                answerValidationErrors, finalizerAttempts, newTraceOrder,
                phase, currentStep, finalizerTrigger, lastFinalizerInvalid);
    }

    AgentState withToolCalls(AgentMessage assistantMessage, List<AgentToolCall> calls) {
        List<AgentMessage> nextMessages = new ArrayList<>(messages);
        nextMessages.add(assistantMessage);
        return copy(nextMessages, evidence, executedToolCalls, toolExecutions, calls,
                stepCount, toolCallCount, promptTokens, completionTokens, 0,
                answerValidationErrors, finalizerAttempts, traceOrder,
                AgentWorkflowPhase.TOOL_EXECUTION, AgentStepType.TOOL_CALL,
                finalizerTrigger, lastFinalizerInvalid);
    }

    AgentState consumePendingTool() {
        List<AgentToolCall> next = pendingToolCalls.size() <= 1
                ? List.of() : pendingToolCalls.subList(1, pendingToolCalls.size());
        return copy(messages, evidence, executedToolCalls, toolExecutions, next,
                stepCount, toolCallCount, promptTokens, completionTokens,
                consecutiveProtocolErrors, answerValidationErrors, finalizerAttempts, traceOrder,
                phase, currentStep, finalizerTrigger, lastFinalizerInvalid);
    }

    AgentState clearPendingTools() {
        return copy(messages, evidence, executedToolCalls, toolExecutions, List.of(),
                stepCount, toolCallCount, promptTokens, completionTokens,
                consecutiveProtocolErrors, answerValidationErrors, finalizerAttempts, traceOrder,
                phase, currentStep, finalizerTrigger, lastFinalizerInvalid);
    }

    AgentState markToolCall(AgentToolCall call) {
        Set<String> keys = new LinkedHashSet<>(executedToolCalls);
        keys.add(toolKey(call));
        Map<String, Integer> executions = new LinkedHashMap<>(toolExecutions);
        executions.merge(call.name() == null ? "" : call.name(), 1, Integer::sum);
        return copy(messages, evidence, keys, executions, pendingToolCalls,
                stepCount, toolCallCount + 1, promptTokens, completionTokens,
                consecutiveProtocolErrors, answerValidationErrors, finalizerAttempts, traceOrder,
                AgentWorkflowPhase.TOOL_EXECUTION, AgentStepType.TOOL_CALL,
                finalizerTrigger, lastFinalizerInvalid);
    }

    boolean hasExecuted(AgentToolCall call) { return executedToolCalls.contains(toolKey(call)); }

    int toolExecutionCount(String name) { return toolExecutions.getOrDefault(name == null ? "" : name, 0); }

    AgentState withProtocolError() {
        return copy(messages, evidence, executedToolCalls, toolExecutions, pendingToolCalls,
                stepCount, toolCallCount, promptTokens, completionTokens,
                consecutiveProtocolErrors + 1, answerValidationErrors, finalizerAttempts, traceOrder,
                phase, currentStep, finalizerTrigger, lastFinalizerInvalid);
    }

    AgentState withValidationError() {
        return copy(messages, evidence, executedToolCalls, toolExecutions, pendingToolCalls,
                stepCount, toolCallCount, promptTokens, completionTokens,
                consecutiveProtocolErrors, answerValidationErrors + 1, finalizerAttempts, traceOrder,
                AgentWorkflowPhase.EVIDENCE_VALIDATION, currentStep, finalizerTrigger, lastFinalizerInvalid);
    }

    AgentState beginFinalizer(String trigger) {
        return copy(messages, evidence, executedToolCalls, toolExecutions, List.of(),
                stepCount + 1, toolCallCount, promptTokens, completionTokens,
                consecutiveProtocolErrors, answerValidationErrors, finalizerAttempts + 1, traceOrder,
                AgentWorkflowPhase.FINALIZING, AgentStepType.FINAL_ANSWER, trigger, lastFinalizerInvalid);
    }

    AgentState retryFinalizer(String invalid) {
        return copy(messages, evidence, executedToolCalls, toolExecutions, List.of(),
                stepCount, toolCallCount, promptTokens, completionTokens,
                consecutiveProtocolErrors, answerValidationErrors, finalizerAttempts, traceOrder,
                AgentWorkflowPhase.FINALIZING, AgentStepType.FINAL_ANSWER, finalizerTrigger, invalid);
    }

    AgentState beginPresentation(boolean modelAttempt) {
        return copy(messages, evidence, executedToolCalls, toolExecutions, List.of(),
                stepCount + (modelAttempt ? 1 : 0), toolCallCount, promptTokens, completionTokens,
                consecutiveProtocolErrors, answerValidationErrors, finalizerAttempts, traceOrder,
                AgentWorkflowPhase.FINALIZING, AgentStepType.FINAL_ANSWER,
                finalizerTrigger, lastFinalizerInvalid);
    }

    AgentState withTraceAndUsage(int newTraceOrder, int prompt, int completion) {
        return copy(messages, evidence, executedToolCalls, toolExecutions, pendingToolCalls,
                stepCount, toolCallCount, promptTokens + Math.max(0, prompt),
                completionTokens + Math.max(0, completion), consecutiveProtocolErrors,
                answerValidationErrors, finalizerAttempts, newTraceOrder,
                phase, currentStep, finalizerTrigger, lastFinalizerInvalid);
    }

    AgentState withPhase(AgentWorkflowPhase next, AgentStepType step) {
        return copy(messages, evidence, executedToolCalls, toolExecutions, pendingToolCalls,
                stepCount, toolCallCount, promptTokens, completionTokens,
                consecutiveProtocolErrors, answerValidationErrors, finalizerAttempts, traceOrder,
                next, step, finalizerTrigger, lastFinalizerInvalid);
    }

    AgentState withTraceOrder(int order) {
        return copy(messages, evidence, executedToolCalls, toolExecutions, pendingToolCalls,
                stepCount, toolCallCount, promptTokens, completionTokens,
                consecutiveProtocolErrors, answerValidationErrors, finalizerAttempts, order,
                phase, currentStep, finalizerTrigger, lastFinalizerInvalid);
    }

    private AgentState copy(List<AgentMessage> newMessages,
                            Map<String, ConversationRetrievalCandidate> newEvidence,
                            Set<String> newExecuted, Map<String, Integer> newExecutions,
                            List<AgentToolCall> newPending, int newSteps, int newToolCalls,
                            int newPrompt, int newCompletion, int newProtocolErrors,
                            int newValidationErrors, int newFinalizerAttempts, int newTraceOrder,
                            AgentWorkflowPhase newPhase, AgentStepType newCurrentStep,
                            String newTrigger, String newLastInvalid) {
        if (newPhase == null || !phase.canTransitionTo(newPhase)) {
            throw new IllegalStateException("Illegal Agent phase transition: " + phase + " -> " + newPhase);
        }
        return new AgentState(runRequest, budget, startedAt, runtimeConfig, streamingSupported,
                newMessages, newEvidence, newExecuted, newExecutions, newPending,
                newSteps, newToolCalls, newPrompt, newCompletion, newProtocolErrors,
                newValidationErrors, newFinalizerAttempts, newTraceOrder,
                newPhase, newCurrentStep, newTrigger, newLastInvalid);
    }

    @Override
    public Map<String, ConversationRetrievalCandidate> evidence() {
        return immutableEvidence(evidence);
    }

    private static Map<String, ConversationRetrievalCandidate> immutableEvidence(
            Map<String, ConversationRetrievalCandidate> source) {
        Map<String, ConversationRetrievalCandidate> copied = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, candidate) -> copied.put(key, copyCandidate(candidate)));
        }
        return Collections.unmodifiableMap(copied);
    }

    private static ConversationRetrievalCandidate copyCandidate(ConversationRetrievalCandidate source) {
        if (source == null) return null;
        return ConversationRetrievalCandidate.builder()
                .segmentId(source.getSegmentId()).kbId(source.getKbId()).assetId(source.getAssetId())
                .assetType(source.getAssetType()).resultType(source.getResultType())
                .segmentType(source.getSegmentType()).title(source.getTitle()).sourceRef(source.getSourceRef())
                .content(source.getContent()).snippet(source.getSnippet()).score(source.getScore())
                .pageNo(source.getPageNo()).anchor(copyAnchor(source.getAnchor()))
                .explain(copyExplain(source.getExplain())).build();
    }

    private static ConversationRetrievalCandidate.Anchor copyAnchor(
            ConversationRetrievalCandidate.Anchor source) {
        if (source == null) return null;
        List<BboxInfo> bbox = source.getBbox() == null ? null
                : source.getBbox().stream().map(AgentState::copyBbox).toList();
        return ConversationRetrievalCandidate.Anchor.builder()
                .pageNo(source.getPageNo()).chunkOrder(source.getChunkOrder()).bbox(bbox)
                .imageWidth(source.getImageWidth()).imageHeight(source.getImageHeight()).build();
    }

    private static BboxInfo copyBbox(BboxInfo source) {
        if (source == null) return null;
        BboxInfo.Bbox sourceBox = source.getBbox();
        BboxInfo.Bbox box = sourceBox == null ? null : BboxInfo.Bbox.builder()
                .l(sourceBox.getL()).t(sourceBox.getT()).r(sourceBox.getR()).b(sourceBox.getB())
                .coordOrigin(sourceBox.getCoordOrigin()).build();
        return BboxInfo.builder().bbox(box).pageNo(source.getPageNo()).build();
    }

    private static ConversationRetrievalCandidate.Explain copyExplain(
            ConversationRetrievalCandidate.Explain source) {
        if (source == null) return null;
        return ConversationRetrievalCandidate.Explain.builder()
                .strategyEffective(source.getStrategyEffective())
                .hitSources(source.getHitSources() == null ? null : List.copyOf(source.getHitSources()))
                .build();
    }

    private static String toolKey(AgentToolCall call) {
        return call.id() != null && !call.id().isBlank()
                ? call.id() : String.valueOf(call.name()) + "\n" + String.valueOf(call.arguments());
    }
}
