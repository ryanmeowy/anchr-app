package com.anchr.core.conversation.domain.repository;

import com.anchr.core.conversation.domain.model.AgentRun;
import com.anchr.core.conversation.domain.model.AgentStep;

import java.util.List;
import java.util.Optional;

public interface AgentTraceRepository {
    void insertRun(AgentRun run);
    boolean finishWorkflowRun(AgentRun run);
    boolean transitionRun(AgentRun run, String expectedStatus);
    boolean markTraditionalFallback(String runId, String fallbackReason);
    boolean addRunTokenUsage(String runId, int promptTokens, int completionTokens);
    void saveStep(AgentStep step);
    boolean lockRun(String runId);
    Optional<AgentRun> findRun(String runId);
    List<AgentRun> findRecoverableRuns(String userId, int limit);
    List<AgentStep> findSteps(String runId);
    List<String> findOlderTerminalRunIds(String sessionId, String currentTurnId);
    List<String> findRunIdsBySessionId(String sessionId);
    void deleteStepsByRunIds(List<String> runIds);
    void deleteRunsBySessionId(String sessionId);
}
