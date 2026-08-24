package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.domain.model.AgentRun;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentRunFinalizer {
    private static final String TRADITIONAL_FALLBACK_REASON = "traditional_rag_fallback";
    private static final Set<String> DEGRADED_REASONS = Set.of(
            "agent_budget_exhausted",
            "agent_evidence_finalization_unavailable",
            "agent_evidence_finalization_failed"
    );

    private final AgentTraceRepository repository;
    private final MeterRegistry meterRegistry;

    public void markTurnSaved(String runId) {
        AgentRun run = awaitingRun(runId);
        if (run == null) return;
        AgentRunStatus status = TRADITIONAL_FALLBACK_REASON.equals(run.getFallbackReason())
                ? AgentRunStatus.FALLBACK
                : isDegradedReason(run.getFallbackReason()) ? AgentRunStatus.DEGRADED : AgentRunStatus.COMPLETED;
        run.setStatus(status.name());
        run.setFinishedAt(System.currentTimeMillis());
        run.setLatencyMs(run.getFinishedAt() - run.getStartedAt());
        if (repository.transitionRun(run, AgentRunStatus.AWAITING_TURN.name())) {
            recordMetrics(run, status);
        } else {
            log.warn("Agent run turn completion ignored because status changed, runId={}", runId);
        }
    }

    private boolean isDegradedReason(String reason) {
        return StringUtils.hasText(reason)
                && (DEGRADED_REASONS.contains(reason) || reason.startsWith("agent_protocol_error:"));
    }

    public void markTurnFailed(String runId) {
        AgentRun run = awaitingRun(runId);
        if (run == null) return;
        run.setStatus(AgentRunStatus.FAILED.name());
        run.setErrorCode("turn_persistence_failed");
        run.setFinishedAt(System.currentTimeMillis());
        run.setLatencyMs(run.getFinishedAt() - run.getStartedAt());
        if (repository.transitionRun(run, AgentRunStatus.AWAITING_TURN.name())) {
            recordMetrics(run, AgentRunStatus.FAILED);
        } else {
            log.warn("Agent run turn failure ignored because status changed, runId={}", runId);
        }
    }

    public void prepareTraditionalFallback(String runId) {
        if (!StringUtils.hasText(runId)) return;
        if (!repository.markTraditionalFallback(runId, TRADITIONAL_FALLBACK_REASON)) {
            log.warn("Traditional fallback marker ignored because run is not awaiting turn, runId={}", runId);
        }
    }

    private AgentRun awaitingRun(String runId) {
        if (!StringUtils.hasText(runId)) return null;
        return repository.findRun(runId)
                .filter(run -> AgentRunStatus.AWAITING_TURN.name().equals(run.getStatus()))
                .orElse(null);
    }

    private void recordMetrics(AgentRun run, AgentRunStatus status) {
        meterRegistry.counter("agent.run.count", "status", status.name()).increment();
        meterRegistry.summary("agent.steps").record(run.getStepCount());
        meterRegistry.summary("agent.tool.calls").record(run.getToolCallCount());
        meterRegistry.summary("agent.run.tokens", "type", "prompt").record(run.getPromptTokens());
        meterRegistry.summary("agent.run.tokens", "type", "completion").record(run.getCompletionTokens());
    }
}
