package com.anchr.core.conversation.domain.repository;

import com.anchr.core.conversation.domain.model.AgentRun;
import com.anchr.core.conversation.domain.model.AgentStep;

import java.util.List;
import java.util.Optional;

public interface AgentTraceRepository {
    void saveRun(AgentRun run);
    void saveStep(AgentStep step);
    Optional<AgentRun> findRun(String runId);
    List<AgentStep> findSteps(String runId);
    void deleteBySessionId(String sessionId);
}
