package com.anchr.core.conversation.domain.repository;

import com.anchr.core.conversation.domain.model.AgentRun;
import com.anchr.core.conversation.domain.model.AgentStep;

import java.util.List;
import java.util.Optional;

public interface AgentTraceRepository {
    void saveRun(AgentRun run);
    void saveStep(AgentStep step);
    Optional<AgentRun> findRun(String runId);
    List<AgentRun> findRecoverableRuns(String userId, int limit);
    List<AgentStep> findSteps(String runId);
    List<String> findRunIdsBySessionId(String sessionId);
    void deleteStepsByRunIds(List<String> runIds);
    void deleteRunsBySessionId(String sessionId);
}
