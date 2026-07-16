package com.anchr.core.conversation.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface AgentTraceMapper {
    int upsertRun(AgentRunRecord record);
    int insertStep(AgentStepRecord record);
    Optional<AgentRunRecord> findRun(@Param("runId") String runId);
    List<AgentStepRecord> findSteps(@Param("runId") String runId);
    int deleteRunsBySessionId(@Param("sessionId") String sessionId);
}
