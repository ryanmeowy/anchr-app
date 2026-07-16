package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.domain.model.AgentRun;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class AgentRunFinalizer {
    private static final Set<String> FALLBACK_REASONS = Set.of(
            "agent_budget_exhausted"
    );

    private final AgentTraceRepository repository;
    private final MeterRegistry meterRegistry;

    public void markTurnSaved(String runId) {
        AgentRun run = awaitingRun(runId);
        if (run == null) return;
        AgentRunStatus status = isFallbackReason(run.getFallbackReason())
                ? AgentRunStatus.FALLBACK : AgentRunStatus.COMPLETED;
        run.setStatus(status.name());
        run.setFinishedAt(System.currentTimeMillis());
        run.setLatencyMs(run.getFinishedAt() - run.getStartedAt());
        repository.saveRun(run);
        recordMetrics(run, status);
    }

    private boolean isFallbackReason(String reason) {
        return StringUtils.hasText(reason)
                && (FALLBACK_REASONS.contains(reason) || reason.startsWith("agent_protocol_error:"));
    }

    public void markTurnFailed(String runId) {
        AgentRun run = awaitingRun(runId);
        if (run == null) return;
        run.setStatus(AgentRunStatus.FAILED.name());
        run.setErrorCode("turn_persistence_failed");
        run.setFinishedAt(System.currentTimeMillis());
        run.setLatencyMs(run.getFinishedAt() - run.getStartedAt());
        repository.saveRun(run);
        recordMetrics(run, AgentRunStatus.FAILED);
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
