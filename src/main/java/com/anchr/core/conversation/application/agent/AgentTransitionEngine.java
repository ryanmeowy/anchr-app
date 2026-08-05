package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AgentMessage;
import com.anchr.core.conversation.application.model.AgentTokenUsage;
import com.anchr.core.conversation.application.model.AgentToolCall;
import com.anchr.core.conversation.application.model.AnswerStatus;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.anchr.core.conversation.application.constant.AgentConstant.FINALIZER_MAX_ATTEMPTS;
import static com.anchr.core.conversation.application.constant.AgentConstant.FINALIZER_EVIDENCE_ITEM_CHARS;
import static com.anchr.core.conversation.application.constant.AgentConstant.FINALIZER_MIN_REMAINING_MILLIS;
import static com.anchr.core.conversation.application.constant.AgentConstant.MAX_FINALIZER_EVIDENCE;
import static com.anchr.core.conversation.application.constant.AgentConstant.MAX_FINALIZER_EVIDENCE_CHARS;
import static com.anchr.core.conversation.application.constant.AgentConstant.MAX_PROTOCOL_ERRORS;
import static com.anchr.core.conversation.application.constant.AgentConstant.MAX_READ_DOCUMENT_CALLS;

/** Pure control plane: State + Event -> next State, one Command, Signals and optional terminal. */
public final class AgentTransitionEngine {
    private static final String LOCAL_CLARIFICATION =
            "我还缺少足够信息来完成这个请求。请补充具体问题或要处理的文档。";
    private static final String LOCAL_PROTOCOL_FALLBACK =
            "模型未能按要求完成工具调用。请重试，或指定要查询的文档与问题。";

    public AgentTransition transition(AgentState state, AgentEvent event) {
        if (state.phase().terminal()) {
            throw new IllegalStateException("Terminal Agent state cannot accept event: " + state.phase());
        }
        if (event instanceof AgentEvent.CancellationRequested) {
            return terminal(state, "查询已取消。", AnswerStatus.CANCELLED,
                    "agent_run_cancelled", AgentRunStatus.CANCELLED, null, null, List.of());
        }
        if (event instanceof AgentEvent.RunStarted started) return runStarted(state, started);
        if (event instanceof AgentEvent.ModelCompleted completed) return modelCompleted(state, completed);
        if (event instanceof AgentEvent.ModelFailed failed) return modelFailed(state, failed);
        if (event instanceof AgentEvent.ToolCompleted completed) return toolCompleted(state, completed);
        if (event instanceof AgentEvent.ToolFailed failed) return toolFailed(state, failed);
        if (event instanceof AgentEvent.AnswerAccepted accepted) return answerAccepted(state, accepted);
        if (event instanceof AgentEvent.AnswerRejected rejected) return answerRejected(state, rejected);
        if (event instanceof AgentEvent.AnswerVerificationFailed failed) {
            return answerVerificationFailed(state, failed);
        }
        if (event instanceof AgentEvent.FinalizerModelCompleted completed) return finalizerCompleted(state, completed);
        if (event instanceof AgentEvent.FinalizerModelFailed failed) return finalizerFailed(state, failed);
        if (event instanceof AgentEvent.PresentationCompleted completed) return presentationCompleted(state, completed);
        if (event instanceof AgentEvent.PresentationFailed failed) return presentationFailed(state, failed);
        throw new IllegalArgumentException("Unsupported Agent event: " + event);
    }

    private AgentTransition runStarted(AgentState state, AgentEvent.RunStarted event) {
        List<AgentSignal> signals = new ArrayList<>();
        signals.add(new AgentSignal.RunStarted());
        signals.add(progress("agent_thinking", "run_started", state.stepCount(), Map.of(
                "sessionId", state.runRequest().sessionId(), "turnId", state.runRequest().turnId())));
        return schedulePlanning(state, event.occurredAt(), signals);
    }

    private AgentTransition modelCompleted(AgentState state, AgentEvent.ModelCompleted event) {
        int order = state.traceOrder() + 1;
        var response = event.response();
        AgentState next = state.withModelResult(response.usage().promptTokens(),
                response.usage().completionTokens(), order);
        int callCount = event.decision() instanceof AgentModelDecision.ToolCalls tools
                ? tools.calls().size() : 0;
        List<AgentSignal> signals = new ArrayList<>();
        signals.add(new AgentSignal.Trace(order, AgentStepType.MODEL_DECISION, state.stepCount(),
                response.finishReason(),
                Map.of("messageCount", state.messages().size(), "toolsEnabled", true),
                Map.of("toolCallCount", callCount,
                        "hasContent", StringUtils.hasText(response.content()),
                        "model", safe(response.model()),
                        "toolCallMode", effectiveToolCallMode(state)),
                response.usage(), event.durationMs(), null));
        signals.add(progress("agent_thinking", "decision_completed", next.stepCount(), Map.of(
                "stepOrder", order, "durationMs", event.durationMs(),
                "messageCount", state.messages().size(), "toolCallCount", callCount,
                "promptTokens", response.usage().promptTokens(),
                "completionTokens", response.usage().completionTokens(),
                "model", safe(response.model()), "decision", decisionName(event.decision(), response.content()))));
        if (event.decision() instanceof AgentModelDecision.ToolCalls calls) {
            if (calls.calls().isEmpty()) return protocolError(next, "MISSING_ACTION", event.occurredAt(), signals);
            next = next.withToolCalls(AgentMessage.assistantToolCalls(
                    response.content(), response.reasoningContent(), calls.calls()), calls.calls());
            return scheduleNextTool(next, event.occurredAt(), signals);
        }
        if (event.decision() instanceof AgentModelDecision.FinalAnswer answer) {
            next = next.withPhase(AgentWorkflowPhase.EVIDENCE_VALIDATION, AgentStepType.FINAL_ANSWER);
            return new AgentTransition(next,
                    new AgentCommand.VerifyAnswer(new UnverifiedAgentAnswer(answer.answer(), null, null)),
                    signals, null);
        }
        return protocolError(next, ((AgentModelDecision.ProtocolError) event.decision()).code(),
                event.occurredAt(), signals);
    }

    private AgentTransition modelFailed(AgentState state, AgentEvent.ModelFailed event) {
        int order = state.traceOrder() + 1;
        AgentState next = state.withTraceOrder(order);
        List<AgentSignal> signals = List.of(
                new AgentSignal.Trace(order, AgentStepType.MODEL_DECISION, state.stepCount(),
                        "MODEL_CALL_FAILED", Map.of("messageCount", state.messages().size(), "toolsEnabled", true),
                        Map.of("toolCallCount", 0, "hasContent", false, "decision", "MODEL_ERROR"),
                        AgentTokenUsage.EMPTY, event.durationMs(), "MODEL_DECISION_FAILED"),
                progress("agent_thinking", "decision_failed", next.stepCount(), Map.of(
                        "stepOrder", order, "durationMs", event.durationMs(),
                        "messageCount", state.messages().size(), "toolCallCount", 0,
                        "promptTokens", 0, "completionTokens", 0, "decision", "MODEL_ERROR",
                        "success", false, "errorCode", "MODEL_DECISION_FAILED")));
        return terminal(next, "", AnswerStatus.MODEL_FALLBACK, "agent_workflow_failed",
                AgentRunStatus.FAILED, null, event.cause(), signals);
    }

    private AgentTransition toolCompleted(AgentState state, AgentEvent.ToolCompleted event) {
        AgentToolResult result = event.result();
        AgentState next = state.registerEvidence(result.evidence())
                .appendMessage(AgentMessage.tool(event.call().id(), event.call().name(), event.modelMessage()));
        int order = next.traceOrder() + 1;
        next = next.withTraceOrder(order);
        Map<String, Object> summary = toolSummary(event.call(), result, event.durationMs());
        List<AgentSignal> signals = new ArrayList<>();
        signals.add(new AgentSignal.Trace(order,
                result.success() ? AgentStepType.TOOL_RESULT : AgentStepType.FAILED,
                event.attempt(), result.success() ? "SUCCESS" : "ERROR",
                Map.of("tool", safe(event.call().name()), "callId", safe(event.call().id())),
                summary, AgentTokenUsage.EMPTY, event.durationMs(), result.errorCode()));
        Map<String, Object> details = new LinkedHashMap<>(summary);
        details.put("stepOrder", order);
        details.put("toolCallOrder", event.attempt());
        signals.add(progress("tool_result", result.success() ? "completed" : "failed",
                next.stepCount(), details));
        if (result.finalAnswer() != null) {
            next = next.clearPendingTools()
                    .withPhase(AgentWorkflowPhase.EVIDENCE_VALIDATION, AgentStepType.FINAL_ANSWER);
            return new AgentTransition(next, new AgentCommand.VerifyAnswer(new UnverifiedAgentAnswer(
                    result.finalAnswer(), event.call().id(), event.call().name())), signals, null);
        }
        if (result.deferredTask() != null) {
            next = next.clearPendingTools();
            Map<String, Object> taskDetails = new LinkedHashMap<>();
            taskDetails.put("callId", safe(event.call().id()));
            taskDetails.put("stepOrder", order);
            taskDetails.put("taskType", result.deferredTask().type());
            if (summary.get("documentCount") != null) taskDetails.put("documentCount", summary.get("documentCount"));
            signals.add(progress("task_queued", "completed", next.stepCount(), taskDetails));
            return terminal(next, "已创建文档处理任务，完成后会更新本条回复。", AnswerStatus.PROCESSING,
                    null, AgentRunStatus.WAITING_TASK, result.deferredTask(), null, signals);
        }
        return scheduleNextTool(next, event.occurredAt(), signals);
    }

    private AgentTransition toolFailed(AgentState state, AgentEvent.ToolFailed event) {
        return terminal(state, "", AnswerStatus.MODEL_FALLBACK, "agent_workflow_failed",
                AgentRunStatus.FAILED, null, event.cause(), List.of());
    }

    private AgentTransition answerAccepted(AgentState state, AgentEvent.AnswerAccepted event) {
        boolean noEvidence = event.answer() instanceof VerifiedNoEvidenceAnswer;
        boolean cited = event.answer() instanceof VerifiedCitedAnswer citedAnswer
                && !citedAnswer.citations().isEmpty();
        boolean modelAttempt = !noEvidence && !cited && state.streamingSupported()
                && StringUtils.hasText(event.answer().answer())
                && state.budget().remainingMillis(event.occurredAt()) >= 1_000L;
        AgentState next = state.beginPresentation(modelAttempt);
        List<AgentSignal> signals = noEvidence
                ? List.of(new AgentSignal.NoEvidenceDeclared()) : List.of();
        int order = modelAttempt ? next.traceOrder() + 1 : next.traceOrder();
        Duration timeout = next.budget().boundedTimeout(next.runtimeConfig().modelTimeout(), event.occurredAt());
        return new AgentTransition(next, new AgentCommand.PresentAnswer(event.answer(), modelAttempt,
                next.stepCount(), order, event.occurredAt(), timeout), signals, null);
    }

    private AgentTransition answerRejected(AgentState state, AgentEvent.AnswerRejected event) {
        AgentState next = state.withValidationError();
        String tool = StringUtils.hasText(event.validationToolName())
                ? event.validationToolName() : "deliver_answer";
        List<AgentSignal> signals = new ArrayList<>(List.of(new AgentSignal.AnswerValidationRejected(
                next.answerValidationErrors(), tool, safe(event.validationToolCallId()),
                event.code(), event.fallbackReason(), event.message())));
        if (next.answerValidationErrors() <= 1
                && !next.budget().exhausted(next.stepCount(), next.toolCallCount(), event.occurredAt())) {
            String error = errorJson(event.code(), event.message());
            if (StringUtils.hasText(event.validationToolCallId())) {
                next = next.appendMessage(AgentMessage.tool(event.validationToolCallId(),
                        StringUtils.hasText(event.validationToolName()) ? event.validationToolName() : "deliver_answer",
                        error));
            } else {
                next = next.appendMessage(AgentMessage.user("最终回答校验失败：" + error));
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("errorCode", event.code());
            details.put("success", false);
            if (StringUtils.hasText(event.validationToolCallId())) {
                details.put("callId", event.validationToolCallId());
                details.put("tool", StringUtils.hasText(event.validationToolName())
                        ? event.validationToolName() : "deliver_answer");
                details.put("stepOrder", next.traceOrder());
                details.put("toolCallOrder", next.toolCallCount());
            }
            signals.add(progress("tool_result", "answer_repair_required", next.stepCount(), details));
            return schedulePlanning(next, event.occurredAt(), signals);
        }
        return terminal(next, "当前证据不足以生成可靠回答。请缩小问题范围或指定文档后重试。",
                AnswerStatus.NO_EVIDENCE, event.fallbackReason(), AgentRunStatus.COMPLETED,
                null, null, signals);
    }

    private AgentTransition answerVerificationFailed(AgentState state,
                                                       AgentEvent.AnswerVerificationFailed event) {
        return terminal(state, "", AnswerStatus.MODEL_FALLBACK, "agent_workflow_failed",
                AgentRunStatus.FAILED, null, event.cause(), List.of());
    }

    private AgentTransition finalizerCompleted(AgentState state, AgentEvent.FinalizerModelCompleted event) {
        int order = state.traceOrder() + 1;
        boolean valid = event.validation() instanceof AgentAnswerValidationOutcome.Verified;
        VerifiedAgentAnswer verified = valid
                ? ((AgentAnswerValidationOutcome.Verified) event.validation()).answer() : null;
        int citations = verified instanceof VerifiedCitedAnswer cited ? cited.citations().size() : 0;
        AgentState next = state.withTraceAndUsage(order, event.usage().promptTokens(), event.usage().completionTokens());
        List<AgentSignal> signals = new ArrayList<>();
        signals.add(new AgentSignal.Trace(order,
                valid ? AgentStepType.FINAL_ANSWER : AgentStepType.FAILED,
                state.finalizerAttempts(), valid ? "EVIDENCE_FINALIZED" : "EVIDENCE_FINALIZATION_INVALID",
                Map.of("phase", "EVIDENCE_FINALIZATION", "trigger", safe(state.finalizerTrigger()),
                        "evidenceCount", selectedEvidenceCount(state)),
                Map.of("hasContent", event.hasContent(), "citationCount", citations),
                event.usage(), event.durationMs(), valid ? null : "INVALID_FINALIZER_RESPONSE"));
        if (valid) {
            signals.add(progress("agent_thinking", "evidence_finalized", next.stepCount(), Map.of(
                    "stepOrder", order, "decision", "FINAL_RESPONSE", "evidenceCount", selectedEvidenceCount(next),
                    "citationCount", citations, "durationMs", event.durationMs())));
            AgentTransition presentation = answerAccepted(next,
                    new AgentEvent.AnswerAccepted(verified, event.occurredAt()));
            signals.addAll(presentation.signals());
            return new AgentTransition(presentation.nextState(), presentation.command(), signals, presentation.terminal());
        }
        next = next.retryFinalizer("回答为空、JSON 非法、引用缺失或引用不属于当前证据");
        return retryOrFailFinalizer(next, event.occurredAt(), signals);
    }

    private AgentTransition finalizerFailed(AgentState state, AgentEvent.FinalizerModelFailed event) {
        int order = state.traceOrder() + 1;
        AgentState next = state.withTraceOrder(order).retryFinalizer("模型调用失败");
        List<AgentSignal> signals = new ArrayList<>();
        signals.add(new AgentSignal.EffectFailure("EVIDENCE_FINALIZATION", event.cause()));
        signals.add(new AgentSignal.Trace(order, AgentStepType.FAILED, state.finalizerAttempts(),
                "EVIDENCE_FINALIZATION_FAILED",
                Map.of("phase", "EVIDENCE_FINALIZATION", "trigger", safe(state.finalizerTrigger()),
                        "evidenceCount", selectedEvidenceCount(state)), Map.of(), AgentTokenUsage.EMPTY,
                event.durationMs(), "EVIDENCE_FINALIZATION_FAILED"));
        signals.add(progress("agent_thinking", "evidence_finalization_failed", next.stepCount(), Map.of(
                "stepOrder", order, "decision", "FINAL_RESPONSE", "evidenceCount", selectedEvidenceCount(next),
                "success", false, "errorCode", "EVIDENCE_FINALIZATION_FAILED",
                "durationMs", event.durationMs())));
        return retryOrFailFinalizer(next, event.occurredAt(), signals);
    }

    private AgentTransition presentationCompleted(AgentState state, AgentEvent.PresentationCompleted event) {
        AgentState next = state;
        List<AgentSignal> signals = new ArrayList<>();
        if (event.modelAttempted()) {
            int order = state.traceOrder() + 1;
            next = state.withTraceAndUsage(order, event.usage().promptTokens(), event.usage().completionTokens());
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("hasContent", StringUtils.hasText(event.answer().answer()));
            output.put("modelCallCount", 1);
            output.put("modelLatencyMs", event.durationMs());
            output.put("streaming", true);
            if (event.firstTokenMs() >= 0) output.put("firstTokenMs", event.firstTokenMs());
            signals.add(new AgentSignal.Trace(order,
                    event.modelSucceeded() ? AgentStepType.FINAL_ANSWER : AgentStepType.FAILED,
                    state.stepCount(), event.modelSucceeded() ? "STREAM_COMPLETED" : "STREAM_FAILED",
                    Map.of("phase", "FINAL_PRESENTATION", "toolsEnabled", false), output,
                    event.usage(), event.durationMs(), event.modelSucceeded() ? null : "final_stream_failed"));
        }
        PresentedAgentAnswer answer = event.answer();
        return terminal(next, answer.answer(), answer.answerStatus(), answer.fallbackReason(),
                AgentRunStatus.COMPLETED, null, null, signals, answer.citations());
    }

    private AgentTransition presentationFailed(AgentState state, AgentEvent.PresentationFailed event) {
        PresentedAgentAnswer fallback = event.fallback();
        AgentEvent.PresentationCompleted completed = new AgentEvent.PresentationCompleted(
                fallback, AgentTokenUsage.EMPTY, event.modelAttempted(), false,
                event.firstTokenMs(), event.durationMs(), event.occurredAt());
        AgentTransition transition = presentationCompleted(state, completed);
        List<AgentSignal> signals = new ArrayList<>();
        signals.add(new AgentSignal.EffectFailure("FINAL_PRESENTATION", event.cause()));
        signals.addAll(transition.signals());
        return new AgentTransition(transition.nextState(), transition.command(), signals,
                transition.terminal());
    }

    private AgentTransition schedulePlanning(AgentState state, long now, List<AgentSignal> signals) {
        if (state.budget().exhausted(state.stepCount(), state.toolCallCount(), now)) {
            return budgetExhausted(state, now, signals);
        }
        AgentState next = state.withPlanningCall();
        String mode = effectiveToolCallMode(next);
        int order = next.traceOrder() + 1;
        signals.add(progress("agent_thinking", "decision_started", next.stepCount(), Map.of(
                "stepOrder", order, "messageCount", next.messages().size(), "toolsEnabled", true,
                "toolCallMode", mode, "decision", "ANALYZING")));
        return new AgentTransition(next, new AgentCommand.CallModel(true, mode, next.stepCount(), order,
                now, next.budget().boundedTimeout(next.runtimeConfig().modelTimeout(), now)), signals, null);
    }

    private AgentTransition scheduleNextTool(AgentState state, long now, List<AgentSignal> signals) {
        if (state.budget().exhausted(state.stepCount(), state.toolCallCount(), now)) {
            return budgetExhausted(state, now, signals);
        }
        if (state.pendingToolCalls().isEmpty()) return schedulePlanning(state, now, signals);
        AgentToolCall call = state.pendingToolCalls().getFirst();
        AgentState next = state.consumePendingTool();
        if (state.hasExecuted(call)) {
            next = next.appendMessage(AgentMessage.tool(call.id(), call.name(),
                    errorJson("DUPLICATE_TOOL_CALL", "相同工具调用已执行，不会重复执行")));
            signals.add(progress("tool_result", "duplicate_rejected", next.stepCount(), Map.of(
                    "tool", safe(call.name()), "callId", safe(call.id()),
                    "success", false, "errorCode", "DUPLICATE_TOOL_CALL")));
            return scheduleNextTool(next, now, signals);
        }
        if ("read_document".equals(call.name())
                && state.toolExecutionCount(call.name()) >= MAX_READ_DOCUMENT_CALLS
                && !state.evidence().isEmpty()) {
            next = next.appendMessage(AgentMessage.tool(call.id(), call.name(),
                    errorJson("READ_LIMIT_REACHED",
                            "已读取足够的连续文档内容，请使用当前证据生成回答；如需全文处理请调用 summarize_documents")));
            int attempt = state.toolExecutionCount(call.name()) + 1;
            int order = next.traceOrder() + 1;
            next = next.withTraceOrder(order);
            Map<String, Object> summary = Map.of("tool", call.name(), "callId", safe(call.id()),
                    "decision", "READ_LIMIT_REACHED", "evidenceCount", next.evidence().size());
            signals.add(new AgentSignal.Trace(order, AgentStepType.TOOL_RESULT, attempt,
                    "READ_LIMIT_REACHED", Map.of("tool", call.name(), "callId", safe(call.id())),
                    summary, AgentTokenUsage.EMPTY, 0, null));
            Map<String, Object> details = new LinkedHashMap<>(summary);
            details.put("stepOrder", order);
            details.put("toolCallOrder", attempt);
            details.put("success", true);
            signals.add(progress("tool_result", "read_limit_reached", next.stepCount(), details));
            return startFinalizer(next, "read_document_call_limit", now, signals);
        }
        next = next.markToolCall(call);
        int order = next.traceOrder() + 1;
        int attempt = next.toolCallCount();
        signals.add(progress("tool_call", "started", next.stepCount(), Map.of(
                "tool", safe(call.name()), "callId", safe(call.id()),
                "toolCallOrder", attempt, "stepOrder", order)));
        return new AgentTransition(next, new AgentCommand.CallTool(call, attempt, order, now), signals, null);
    }

    private AgentTransition protocolError(AgentState state, String code, long now, List<AgentSignal> signals) {
        AgentState next = state.withProtocolError();
        boolean fallback = next.consecutiveProtocolErrors() >= MAX_PROTOCOL_ERRORS;
        signals.add(new AgentSignal.ProtocolError(code, fallback ? "fallback" : "retry",
                next.consecutiveProtocolErrors(), next.stepCount(), next.toolCallCount()));
        if (fallback) {
            if (!next.evidence().isEmpty() && next.budget().remainingMillis(now) > 0) {
                signals.add(progress("agent_thinking", "protocol_finalizing_evidence", next.stepCount(),
                        Map.of("errorCode", code, "evidenceCount", next.evidence().size())));
                return startFinalizer(next, "agent_protocol_error:" + code, now, signals);
            }
            signals.add(progress("agent_thinking", "protocol_fallback", next.stepCount(), Map.of("errorCode", code)));
            return terminal(next, LOCAL_PROTOCOL_FALLBACK, AnswerStatus.MODEL_FALLBACK,
                    "agent_protocol_error:" + code, AgentRunStatus.DEGRADED, null, null, signals);
        }
        next = next.appendMessage(AgentMessage.user("协议错误：" + code
                + "。请调用工具或提交最终回答，不要输出额外文本。"));
        signals.add(progress("agent_thinking", "protocol_retry", next.stepCount(), Map.of("errorCode", code)));
        return schedulePlanning(next, now, signals);
    }

    private AgentTransition startFinalizer(AgentState state, String trigger, long now, List<AgentSignal> signals) {
        if (state.evidence().isEmpty()
                || state.budget().remainingMillis(now) < FINALIZER_MIN_REMAINING_MILLIS) {
            return terminal(state, "已检索到相关资料，但当前处理时间不足以生成可靠回答，请重试。",
                    AnswerStatus.MODEL_FALLBACK, "agent_evidence_finalization_unavailable",
                    AgentRunStatus.DEGRADED, null, null, signals);
        }
        AgentState next = state.beginFinalizer(trigger);
        int order = next.traceOrder() + 1;
        signals.add(progress("agent_thinking", "evidence_finalization_started", next.stepCount(), Map.of(
                "stepOrder", order, "decision", "FINAL_RESPONSE", "evidenceCount", selectedEvidenceCount(next),
                "attempt", next.finalizerAttempts())));
        return new AgentTransition(next, new AgentCommand.CallEvidenceFinalizer(
                next.finalizerAttempts(), order, trigger, next.lastFinalizerInvalid(), now,
                next.budget().boundedTimeout(next.runtimeConfig().modelTimeout(), now)), signals, null);
    }

    private AgentTransition retryOrFailFinalizer(AgentState state, long now, List<AgentSignal> signals) {
        if (state.finalizerAttempts() < FINALIZER_MAX_ATTEMPTS
                && state.budget().remainingMillis(now) >= FINALIZER_MIN_REMAINING_MILLIS) {
            return startFinalizer(state, state.finalizerTrigger(), now, signals);
        }
        return terminal(state, "已检索到相关资料，但模型未能完成可靠的证据回答，请重试。",
                AnswerStatus.MODEL_FALLBACK, "agent_evidence_finalization_failed",
                AgentRunStatus.DEGRADED, null, null, signals);
    }

    private AgentTransition budgetExhausted(AgentState state, long now, List<AgentSignal> signals) {
        if (!state.evidence().isEmpty() && state.budget().remainingMillis(now) > 0) {
            return startFinalizer(state, "agent_budget_exhausted", now, signals);
        }
        return terminal(state, LOCAL_CLARIFICATION, AnswerStatus.NO_EVIDENCE,
                "agent_budget_exhausted", AgentRunStatus.DEGRADED, null, null, signals);
    }

    private AgentTransition terminal(AgentState state, String answer, AnswerStatus answerStatus,
                                     String reason, AgentRunStatus status, AgentDeferredTask task,
                                     RuntimeException cause, List<AgentSignal> signals) {
        return terminal(state, answer, answerStatus, reason, status, task, cause, signals, List.of());
    }

    private AgentTransition terminal(AgentState state, String answer, AnswerStatus answerStatus,
                                     String reason, AgentRunStatus status, AgentDeferredTask task,
                                     RuntimeException cause, List<AgentSignal> signals,
                                     List<com.anchr.core.conversation.domain.model.ConversationCitation> citations) {
        AgentWorkflowPhase phase = status == AgentRunStatus.FAILED ? AgentWorkflowPhase.FAILED
                : status == AgentRunStatus.CANCELLED ? AgentWorkflowPhase.CANCELLED : AgentWorkflowPhase.COMPLETED;
        AgentState next = state.withPhase(phase,
                status == AgentRunStatus.FAILED ? AgentStepType.FAILED : AgentStepType.FINAL_ANSWER);
        List<AgentSignal> all = new ArrayList<>(signals);
        all.add(new AgentSignal.Terminal(status, reason));
        return new AgentTransition(next, null, all,
                new AgentTerminal(answer, answerStatus, reason, citations, task, status, cause));
    }

    private String effectiveToolCallMode(AgentState state) {
        AgentRuntimeSettings.ToolCallMode configured = state.runtimeConfig().toolCallMode();
        return configured == AgentRuntimeSettings.ToolCallMode.AUTO && state.consecutiveProtocolErrors() > 0
                ? AgentRuntimeSettings.ToolCallMode.JSON.name() : configured.name();
    }

    private AgentSignal.Progress progress(String stage, String message, int step, Map<String, Object> details) {
        return new AgentSignal.Progress(stage, message, step, details);
    }

    private Map<String, Object> toolSummary(AgentToolCall call, AgentToolResult result, long durationMs) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tool", safe(call.name()));
        details.put("callId", safe(call.id()));
        details.put("success", result.success());
        details.put("durationMs", Math.max(0L, durationMs));
        details.put("evidenceCount", result.evidence().size());
        result.traceDetails().forEach((key, value) -> {
            if (List.of("evidenceCount", "documentCount", "segmentCount", "citationCount",
                    "hasMore", "taskType", "answerType").contains(key)
                    && (value instanceof Number || value instanceof Boolean || value instanceof String)) {
                details.put(key, value);
            }
        });
        return details;
    }

    private String decisionName(AgentModelDecision decision, String content) {
        if (decision instanceof AgentModelDecision.ToolCalls calls && !calls.calls().isEmpty()) return "TOOL_SELECTION";
        return StringUtils.hasText(content) ? "FINAL_RESPONSE" : "PROTOCOL_RETRY";
    }

    private int selectedEvidenceCount(AgentState state) {
        int count = 0;
        int chars = 0;
        for (var candidate : state.evidence().values()) {
            if (candidate == null || !StringUtils.hasText(candidate.getSegmentId())) continue;
            String content = candidate.getContent();
            if (!StringUtils.hasText(content)) content = candidate.getSnippet();
            int added = Math.min(content == null ? 0 : content.trim().length(),
                    FINALIZER_EVIDENCE_ITEM_CHARS);
            if (count > 0 && chars + added > MAX_FINALIZER_EVIDENCE_CHARS) break;
            count++;
            chars += added;
            if (count >= MAX_FINALIZER_EVIDENCE) break;
        }
        return count;
    }

    private static String errorJson(String code, String message) {
        return "{\"success\":false,\"errorCode\":\"" + escape(code)
                + "\",\"message\":\"" + escape(message) + "\"}";
    }

    private static String escape(String value) {
        return safe(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
