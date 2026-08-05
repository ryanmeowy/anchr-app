package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.model.AgentProgressEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class AgentRunObserver {
    private final AgentTraceRecorder traceRecorder;
    private final MeterRegistry meterRegistry;

    public AgentRunObserver(AgentTraceRecorder traceRecorder, MeterRegistry meterRegistry) {
        this.traceRecorder = traceRecorder;
        this.meterRegistry = meterRegistry;
    }

    public void observe(AgentState previousState, AgentState state, List<AgentSignal> signals,
                        ConversationProgressListener listener) {
        ConversationProgressListener progress = listener == null
                ? ConversationProgressListener.NOOP : listener;
        for (AgentSignal signal : signals) {
            try {
                observeOne(previousState, state, signal, progress);
            } catch (Exception e) {
                log.warn("Agent observer failed, runId={}, signal={}",
                        state.runRequest().runId(), signal.getClass().getSimpleName(), e);
            }
        }
    }

    private void observeOne(AgentState previousState, AgentState state, AgentSignal signal,
                            ConversationProgressListener progress) {
        if (signal instanceof AgentSignal.RunStarted) {
            traceRecorder.start(previousState);
            log.info("Agent workflow started, runId={}, sessionId={}, turnId={}, maxSteps={}, maxToolCalls={}",
                    state.runRequest().runId(), state.runRequest().sessionId(), state.runRequest().turnId(),
                    state.runtimeConfig().maxSteps(), state.runtimeConfig().maxToolCalls());
            return;
        }
        if (signal instanceof AgentSignal.Progress event) {
            progress.onAgentProgress(new AgentProgressEvent(state.runRequest().runId(), event.stage(),
                    event.message(), event.stepCount(), event.details()));
            logProgress(state, event);
            return;
        }
        if (signal instanceof AgentSignal.Trace trace) {
            traceRecorder.recordStep(state, trace.stepOrder(), trace.type(), trace.attempt(),
                    trace.decision(), trace.inputSummary(), trace.outputSummary(), trace.usage(),
                    trace.latencyMs(), trace.errorCode());
            logTrace(state, trace);
            return;
        }
        if (signal instanceof AgentSignal.ProtocolError protocol) {
            meterRegistry.counter("agent.protocol.error", "code", protocol.code(),
                    "outcome", protocol.outcome()).increment();
            log.warn("Agent protocol error, runId={}, code={}, consecutiveErrors={}, steps={}, toolCalls={}",
                    state.runRequest().runId(), protocol.code(), protocol.consecutiveErrors(),
                    protocol.stepCount(), protocol.toolCallCount());
            return;
        }
        if (signal instanceof AgentSignal.NoEvidenceDeclared) {
            meterRegistry.counter("no_evidence.answer.rate", "source", "agent_declared").increment();
            return;
        }
        if (signal instanceof AgentSignal.AnswerValidationRejected rejected) {
            log.warn("Agent answer validation rejected, runId={}, attempt={}, tool={}, callId={}, "
                            + "errorCode={}, fallbackReason={}, message={}",
                    state.runRequest().runId(), rejected.attempt(), safe(rejected.tool()),
                    safe(rejected.callId()), safe(rejected.code()), safe(rejected.fallbackReason()),
                    safe(rejected.message()));
            return;
        }
        if (signal instanceof AgentSignal.EffectFailure failure) {
            RuntimeException cause = failure.cause();
            log.warn("Agent effect failed, runId={}, phase={}, message={}",
                    state.runRequest().runId(), failure.phase(),
                    cause == null ? "" : safe(cause.getMessage()), cause);
            return;
        }
        AgentSignal.Terminal terminal = (AgentSignal.Terminal) signal;
        traceRecorder.finish(state, terminal.status(), terminal.fallbackReason(),
                terminal.status() == AgentRunStatus.FAILED ? terminal.fallbackReason() : null);
        if (terminal.status() != AgentRunStatus.FAILED) {
            meterRegistry.counter("agent.run.result", "status", terminal.status().name()).increment();
        }
        log.info("Agent workflow finished, runId={}, status={}, steps={}, toolCalls={}, promptTokens={}, "
                        + "completionTokens={}, evidenceCount={}, latencyMs={}, fallbackReason={}",
                state.runRequest().runId(), terminal.status(), state.stepCount(), state.toolCallCount(),
                state.promptTokens(), state.completionTokens(), state.evidence().size(),
                System.currentTimeMillis() - state.startedAt(), safe(terminal.fallbackReason()));
    }

    private void logProgress(AgentState state, AgentSignal.Progress progress) {
        var details = progress.details();
        String runId = state.runRequest().runId();
        if ("tool_call".equals(progress.stage()) && "started".equals(progress.message())) {
            log.info("Agent tool execution started, runId={}, step={}, toolCallOrder={}, tool={}, callId={}",
                    runId, value(details, "stepOrder"), value(details, "toolCallOrder"),
                    value(details, "tool"), value(details, "callId"));
        } else if ("tool_result".equals(progress.stage())
                && "duplicate_rejected".equals(progress.message())) {
            log.warn("Agent duplicate tool call rejected, runId={}, tool={}, callId={}",
                    runId, value(details, "tool"), value(details, "callId"));
        } else if ("tool_result".equals(progress.stage())
                && "read_limit_reached".equals(progress.message())) {
            log.info("Agent read-document limit reached, runId={}, step={}, toolCallOrder={}, "
                            + "tool={}, callId={}, evidenceCount={}",
                    runId, value(details, "stepOrder"), value(details, "toolCallOrder"),
                    value(details, "tool"), value(details, "callId"), value(details, "evidenceCount"));
        }
    }

    private void logTrace(AgentState state, AgentSignal.Trace trace) {
        String runId = state.runRequest().runId();
        if (trace.type() == AgentStepType.MODEL_DECISION && trace.errorCode() == null) {
            log.info("Agent model decision completed, runId={}, step={}, traceStep={}, model={}, "
                            + "toolCallMode={}, toolCalls={}, promptTokens={}, completionTokens={}, "
                            + "durationMs={}, finishReason={}",
                    runId, trace.attempt(), trace.stepOrder(), value(trace.outputSummary(), "model"),
                    value(trace.outputSummary(), "toolCallMode"),
                    value(trace.outputSummary(), "toolCallCount"), trace.usage().promptTokens(),
                    trace.usage().completionTokens(), trace.latencyMs(), safe(trace.decision()));
            return;
        }
        if (!trace.inputSummary().containsKey("tool")) return;
        if ("SUCCESS".equals(trace.decision())) {
            log.info("Agent tool execution completed, runId={}, step={}, toolCallOrder={}, tool={}, "
                            + "callId={}, durationMs={}, evidenceCount={}",
                    runId, trace.stepOrder(), trace.attempt(), value(trace.inputSummary(), "tool"),
                    value(trace.inputSummary(), "callId"), trace.latencyMs(),
                    value(trace.outputSummary(), "evidenceCount"));
        } else if ("ERROR".equals(trace.decision())) {
            log.warn("Agent tool execution failed, runId={}, step={}, toolCallOrder={}, tool={}, "
                            + "callId={}, errorCode={}, durationMs={}",
                    runId, trace.stepOrder(), trace.attempt(), value(trace.inputSummary(), "tool"),
                    value(trace.inputSummary(), "callId"), safe(trace.errorCode()), trace.latencyMs());
        }
    }

    private String value(java.util.Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String safe(String value) { return value == null ? "" : value; }
}
