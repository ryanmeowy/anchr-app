package com.anchr.core.conversation.infrastructure.persistence;

import com.anchr.core.conversation.domain.model.AgentRun;
import com.anchr.core.conversation.domain.model.AgentStep;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AgentTraceRepositoryImpl implements AgentTraceRepository {
    private final AgentTraceMapper mapper;

    @Override
    public void saveRun(AgentRun run) {
        if (run == null || !StringUtils.hasText(run.getRunId())) {
            throw new IllegalArgumentException("runId cannot be empty");
        }
        mapper.upsertRun(toRecord(run));
    }

    @Override
    public void saveStep(AgentStep step) {
        if (step == null || !StringUtils.hasText(step.getStepId()) || !StringUtils.hasText(step.getRunId())) {
            throw new IllegalArgumentException("stepId and runId cannot be empty");
        }
        mapper.insertStep(toRecord(step));
    }

    @Override
    public boolean lockRun(String runId) {
        return StringUtils.hasText(runId) && mapper.lockRun(runId) != null;
    }

    @Override
    public Optional<AgentRun> findRun(String runId) {
        return StringUtils.hasText(runId) ? mapper.findRun(runId).map(this::toDomain) : Optional.empty();
    }

    @Override
    public List<AgentRun> findRecoverableRuns(String userId, int limit) {
        if (!StringUtils.hasText(userId)) return List.of();
        int boundedLimit = Math.max(1, Math.min(limit, 20));
        return mapper.findRecoverableRuns(userId, boundedLimit).stream().map(this::toDomain).toList();
    }

    @Override
    public List<AgentStep> findSteps(String runId) {
        return StringUtils.hasText(runId) ? mapper.findSteps(runId).stream().map(this::toDomain).toList() : List.of();
    }

    @Override
    public List<String> findOlderTerminalRunIds(String sessionId, String currentTurnId) {
        return StringUtils.hasText(sessionId) && StringUtils.hasText(currentTurnId)
                ? mapper.findOlderTerminalRunIds(sessionId, currentTurnId)
                : List.of();
    }

    @Override
    public List<String> findRunIdsBySessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? mapper.findRunIdsBySessionId(sessionId) : List.of();
    }

    @Override
    public void deleteStepsByRunIds(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) return;
        List<String> normalized = runIds.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (!normalized.isEmpty()) mapper.deleteStepsByRunIds(normalized);
    }

    @Override
    public void deleteRunsBySessionId(String sessionId) {
        if (StringUtils.hasText(sessionId)) mapper.deleteRunsBySessionId(sessionId);
    }

    private AgentRunRecord toRecord(AgentRun source) {
        AgentRunRecord target = new AgentRunRecord();
        target.setRunId(source.getRunId());
        target.setSessionId(source.getSessionId());
        target.setTurnId(source.getTurnId());
        target.setStatus(source.getStatus());
        target.setCurrentStep(source.getCurrentStep());
        target.setStepCount(source.getStepCount());
        target.setToolCallCount(source.getToolCallCount());
        target.setPromptTokens(source.getPromptTokens());
        target.setCompletionTokens(source.getCompletionTokens());
        target.setLatencyMs(source.getLatencyMs());
        target.setFallbackReason(source.getFallbackReason());
        target.setErrorCode(source.getErrorCode());
        target.setStartedAt(toDateTime(source.getStartedAt()));
        target.setFinishedAt(source.getFinishedAt() == null ? null : toDateTime(source.getFinishedAt()));
        return target;
    }

    private AgentStepRecord toRecord(AgentStep source) {
        AgentStepRecord target = new AgentStepRecord();
        target.setStepId(source.getStepId());
        target.setRunId(source.getRunId());
        target.setStepOrder(source.getStepOrder());
        target.setStepType(source.getStepType());
        target.setAttempt(source.getAttempt());
        target.setStatus(source.getStatus());
        target.setDecisionCode(source.getDecisionCode());
        target.setInputSummary(source.getInputSummaryJson());
        target.setOutputSummary(source.getOutputSummaryJson());
        target.setPromptTokens(source.getPromptTokens());
        target.setCompletionTokens(source.getCompletionTokens());
        target.setLatencyMs(source.getLatencyMs());
        target.setErrorCode(source.getErrorCode());
        target.setCreatedAt(toDateTime(source.getCreatedAt()));
        return target;
    }

    private AgentRun toDomain(AgentRunRecord source) {
        AgentRun target = new AgentRun();
        target.setRunId(source.getRunId());
        target.setSessionId(source.getSessionId());
        target.setTurnId(source.getTurnId());
        target.setStatus(source.getStatus());
        target.setCurrentStep(source.getCurrentStep());
        target.setStepCount(source.getStepCount());
        target.setToolCallCount(source.getToolCallCount());
        target.setPromptTokens(source.getPromptTokens());
        target.setCompletionTokens(source.getCompletionTokens());
        target.setLatencyMs(source.getLatencyMs());
        target.setFallbackReason(source.getFallbackReason());
        target.setErrorCode(source.getErrorCode());
        target.setStartedAt(toEpoch(source.getStartedAt()));
        target.setFinishedAt(source.getFinishedAt() == null ? null : toEpoch(source.getFinishedAt()));
        return target;
    }

    private AgentStep toDomain(AgentStepRecord source) {
        AgentStep target = new AgentStep();
        target.setStepId(source.getStepId());
        target.setRunId(source.getRunId());
        target.setStepOrder(source.getStepOrder());
        target.setStepType(source.getStepType());
        target.setAttempt(source.getAttempt());
        target.setStatus(source.getStatus());
        target.setDecisionCode(source.getDecisionCode());
        target.setInputSummaryJson(source.getInputSummary());
        target.setOutputSummaryJson(source.getOutputSummary());
        target.setPromptTokens(source.getPromptTokens());
        target.setCompletionTokens(source.getCompletionTokens());
        target.setLatencyMs(source.getLatencyMs());
        target.setErrorCode(source.getErrorCode());
        target.setCreatedAt(toEpoch(source.getCreatedAt()));
        return target;
    }

    private LocalDateTime toDateTime(long value) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneId.systemDefault());
    }

    private long toEpoch(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
