package com.anchr.core.conversation.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface AgentTraceMapper {
    int upsertRun(AgentRunRecord record);
    int insertStep(AgentStepRecord record);
    String lockRun(@Param("runId") String runId);
    Optional<AgentRunRecord> findRun(@Param("runId") String runId);
    List<AgentRunRecord> findRecoverableRuns(@Param("userId") String userId,
                                             @Param("limit") int limit);
    List<AgentStepRecord> findSteps(@Param("runId") String runId);
    List<String> findOlderTerminalRunIds(@Param("sessionId") String sessionId,
                                         @Param("currentTurnId") String currentTurnId);
    List<String> findRunIdsBySessionId(@Param("sessionId") String sessionId);
    int deleteStepsByRunIds(@Param("runIds") List<String> runIds);
    int deleteRunsBySessionId(@Param("sessionId") String sessionId);
}
