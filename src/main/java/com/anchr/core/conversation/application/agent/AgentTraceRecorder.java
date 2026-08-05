package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AgentTokenUsage;
import com.anchr.core.conversation.domain.model.AgentRun;
import com.anchr.core.conversation.domain.model.AgentStep;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentTraceRecorder {
    private final AgentTraceRepository repository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public void start(AgentState state) {
        try { saveRun(state, AgentRunStatus.RUNNING, null, null, null); }
        catch (Exception e) { log.warn("Agent run trace start failed, runId={}", state.runRequest().runId(), e); }
    }

    public void recordStep(AgentState state,
                           int stepOrder,
                           AgentStepType type,
                           int attempt,
                           String decision,
                           Map<String, Object> inputSummary,
                           Map<String, Object> outputSummary,
                           AgentTokenUsage usage,
                           long latencyMs,
                           String errorCode) {
        AgentStep step = new AgentStep();
        step.setStepId(UUID.randomUUID().toString());
        step.setRunId(state.runRequest().runId());
        step.setStepOrder(stepOrder);
        step.setStepType(type.name());
        step.setAttempt(Math.max(1, attempt));
        step.setStatus(StringUtils.hasText(errorCode) ? "FAILED" : "COMPLETED");
        step.setDecisionCode(decision);
        step.setInputSummaryJson(toJson(inputSummary));
        step.setOutputSummaryJson(toJson(outputSummary));
        AgentTokenUsage safeUsage = usage == null ? AgentTokenUsage.EMPTY : usage;
        step.setPromptTokens(safeUsage.promptTokens());
        step.setCompletionTokens(safeUsage.completionTokens());
        step.setLatencyMs(latencyMs);
        step.setErrorCode(errorCode);
        step.setCreatedAt(System.currentTimeMillis());
        try { repository.saveStep(step); }
        catch (Exception e) { log.warn("Agent step trace failed, runId={}, step={}", state.runRequest().runId(), type, e); }
        meterRegistry.timer("agent.step.latency", "step", type.name()).record(
                Duration.ofMillis(Math.max(0L, latencyMs)));
        if (safeUsage.totalTokens() > 0) {
            meterRegistry.summary("agent.model.tokens", "step", type.name(), "type", "total")
                    .record(safeUsage.totalTokens());
        }
    }

    public void finish(AgentState state,
                       AgentRunStatus status,
                       String fallbackReason,
                       String errorCode) {
        AgentRunStatus persistedStatus = status == AgentRunStatus.FAILED
                ? AgentRunStatus.FAILED : status == AgentRunStatus.CANCELLED
                ? AgentRunStatus.CANCELLED : status == AgentRunStatus.WAITING_TASK
                ? AgentRunStatus.WAITING_TASK : AgentRunStatus.AWAITING_TURN;
        try { saveRun(state, persistedStatus, state.currentStep().name(), fallbackReason, errorCode); }
        catch (Exception e) { log.warn("Agent run trace finish failed, runId={}", state.runRequest().runId(), e); }
        if (persistedStatus == AgentRunStatus.FAILED) {
            recordRunMetrics(state, AgentRunStatus.FAILED);
        }
    }

    private void recordRunMetrics(AgentState state, AgentRunStatus status) {
        meterRegistry.counter("agent.run.count", "status", status.name()).increment();
        meterRegistry.summary("agent.tool.calls").record(state.toolCallCount());
        meterRegistry.summary("agent.steps").record(state.stepCount());
        meterRegistry.summary("agent.run.tokens", "type", "prompt").record(state.promptTokens());
        meterRegistry.summary("agent.run.tokens", "type", "completion").record(state.completionTokens());
    }

    private void saveRun(AgentState state,
                         AgentRunStatus status,
                         String currentStep,
                         String fallbackReason,
                         String errorCode) {
        AgentRun run = new AgentRun();
        run.setRunId(state.runRequest().runId());
        run.setSessionId(state.runRequest().sessionId());
        run.setTurnId(state.runRequest().turnId());
        run.setStatus(status.name());
        run.setCurrentStep(currentStep == null ? state.currentStep().name() : currentStep);
        run.setStepCount(state.stepCount());
        run.setToolCallCount(state.toolCallCount());
        run.setPromptTokens(state.promptTokens());
        run.setCompletionTokens(state.completionTokens());
        run.setLatencyMs(System.currentTimeMillis() - state.startedAt());
        run.setFallbackReason(fallbackReason);
        run.setErrorCode(errorCode);
        run.setStartedAt(state.startedAt());
        run.setFinishedAt(status == AgentRunStatus.RUNNING || status == AgentRunStatus.AWAITING_TURN
                || status == AgentRunStatus.WAITING_TASK
                ? null : System.currentTimeMillis());
        repository.saveRun(run);
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
