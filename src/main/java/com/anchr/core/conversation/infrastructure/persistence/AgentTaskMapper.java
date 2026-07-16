package com.anchr.core.conversation.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface AgentTaskMapper {
    int upsert(AgentTaskRecord record);
    Optional<AgentTaskRecord> findById(@Param("taskId") String taskId);
    List<AgentTaskRecord> findBySessionId(@Param("sessionId") String sessionId);
    List<AgentTaskRecord> findClaimable(@Param("now") LocalDateTime now, @Param("limit") int limit);
    int claim(@Param("taskId") String taskId, @Param("owner") String owner,
              @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);
    int updateClaimed(@Param("record") AgentTaskRecord record,
                      @Param("expectedLeaseOwner") String expectedLeaseOwner);
    int cancel(@Param("taskId") String taskId, @Param("userId") String userId,
               @Param("now") LocalDateTime now);
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
