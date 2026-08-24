package com.anchr.core.conversation.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface AgentTraceMapper {
    int insertRun(AgentRunRecord record);
    int finishWorkflowRun(AgentRunRecord record);
    int transitionRun(@Param("record") AgentRunRecord record,
                      @Param("expectedStatus") String expectedStatus);
    int markTraditionalFallback(@Param("runId") String runId,
                                @Param("fallbackReason") String fallbackReason);
    int addRunTokenUsage(@Param("runId") String runId,
                         @Param("promptTokens") int promptTokens,
                         @Param("completionTokens") int completionTokens);
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
