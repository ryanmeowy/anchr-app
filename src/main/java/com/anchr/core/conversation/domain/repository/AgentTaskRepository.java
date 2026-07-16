package com.anchr.core.conversation.domain.repository;

import com.anchr.core.conversation.domain.model.AgentTask;
import java.util.List;
import java.util.Optional;

public interface AgentTaskRepository {
    void save(AgentTask task);
    Optional<AgentTask> findById(String taskId);
    List<AgentTask> findBySessionId(String sessionId);
    List<AgentTask> findClaimable(long now, int limit);
    boolean claim(String taskId, String owner, long now, long leaseUntil);
    boolean saveClaimed(AgentTask task, String expectedLeaseOwner);
    boolean cancel(String taskId, String userId, long now);
    void deleteBySessionId(String sessionId);
}
