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

    public void start(AgentRunState state) {
        try { saveRun(state, AgentRunStatus.RUNNING, null, null, null); }
        catch (Exception e) { log.warn("Agent run trace start failed, runId={}", state.getRunRequest().runId(), e); }
    }

    public int recordStep(AgentRunState state,
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
        step.setRunId(state.getRunRequest().runId());
        int stepOrder = state.nextTraceOrder();
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
        catch (Exception e) { log.warn("Agent step trace failed, runId={}, step={}", state.getRunRequest().runId(), type, e); }
        meterRegistry.timer("agent.step.latency", "step", type.name()).record(
                Duration.ofMillis(Math.max(0L, latencyMs)));
        if (safeUsage.totalTokens() > 0) {
            meterRegistry.summary("agent.model.tokens", "step", type.name(), "type", "total")
                    .record(safeUsage.totalTokens());
        }
        return stepOrder;
    }

    public void checkpoint(AgentRunState state) {
        try { saveRun(state, AgentRunStatus.RUNNING, null, null, null); }
        catch (Exception e) { log.warn("Agent run checkpoint failed, runId={}", state.getRunRequest().runId(), e); }
    }

    public void finish(AgentRunState state,
                       AgentRunStatus status,
                       String fallbackReason,
                       String errorCode) {
        AgentRunStatus persistedStatus = status == AgentRunStatus.FAILED
                ? AgentRunStatus.FAILED : status == AgentRunStatus.CANCELLED
                ? AgentRunStatus.CANCELLED : status == AgentRunStatus.WAITING_TASK
                ? AgentRunStatus.WAITING_TASK : AgentRunStatus.AWAITING_TURN;
        try { saveRun(state, persistedStatus, state.getCurrentStep().name(), fallbackReason, errorCode); }
        catch (Exception e) { log.warn("Agent run trace finish failed, runId={}", state.getRunRequest().runId(), e); }
        if (persistedStatus == AgentRunStatus.FAILED) {
            recordRunMetrics(state, AgentRunStatus.FAILED);
        }
    }

    private void recordRunMetrics(AgentRunState state, AgentRunStatus status) {
        meterRegistry.counter("agent.run.count", "status", status.name()).increment();
        meterRegistry.summary("agent.tool.calls").record(state.getToolCallCount());
        meterRegistry.summary("agent.steps").record(state.getStepCount());
        meterRegistry.summary("agent.run.tokens", "type", "prompt").record(state.getPromptTokens());
        meterRegistry.summary("agent.run.tokens", "type", "completion").record(state.getCompletionTokens());
    }

    private void saveRun(AgentRunState state,
                         AgentRunStatus status,
                         String currentStep,
                         String fallbackReason,
                         String errorCode) {
        AgentRun run = new AgentRun();
        run.setRunId(state.getRunRequest().runId());
        run.setSessionId(state.getRunRequest().sessionId());
        run.setTurnId(state.getRunRequest().turnId());
        run.setStatus(status.name());
        run.setCurrentStep(currentStep == null ? state.getCurrentStep().name() : currentStep);
        run.setStepCount(state.getStepCount());
        run.setToolCallCount(state.getToolCallCount());
        run.setPromptTokens(state.getPromptTokens());
        run.setCompletionTokens(state.getCompletionTokens());
        run.setLatencyMs(System.currentTimeMillis() - state.getStartedAt());
        run.setFallbackReason(fallbackReason);
        run.setErrorCode(errorCode);
        run.setStartedAt(state.getStartedAt());
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
