package com.anchr.core.conversation.infrastructure.persistence;

import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.domain.repository.AgentTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AgentTaskRepositoryImpl implements AgentTaskRepository {
    private final AgentTaskMapper mapper;
    @Override public void save(AgentTask task) { mapper.upsert(toRecord(task)); }
    @Override public Optional<AgentTask> findById(String id) { return mapper.findById(id).map(this::toDomain); }
    @Override public List<AgentTask> findByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<String> normalized = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (normalized.isEmpty()) return List.of();
        return mapper.findByIds(normalized).stream()
                .map(this::toDomain).toList();
    }
    @Override public List<AgentTask> findBySessionId(String sessionId) {
        return mapper.findBySessionId(sessionId).stream().map(this::toDomain).toList();
    }
    @Override public List<AgentTask> findClaimable(long now, int limit) {
        return mapper.findClaimable(time(now), Math.max(1, Math.min(20, limit))).stream().map(this::toDomain).toList();
    }
    @Override public boolean claim(String id, String owner, long now, long leaseUntil) {
        return mapper.claim(id, owner, time(now), time(leaseUntil)) == 1;
    }
    @Override public boolean saveClaimed(AgentTask task, String expectedLeaseOwner) {
        return mapper.updateClaimed(toRecord(task), expectedLeaseOwner) == 1;
    }
    @Override public boolean cancel(String taskId, String userId, long now) {
        return mapper.cancel(taskId, userId, time(now)) == 1;
    }
    @Override public void deleteBySessionId(String sessionId) { mapper.deleteBySessionId(sessionId); }
    private AgentTaskRecord toRecord(AgentTask v) {
        AgentTaskRecord r=new AgentTaskRecord(); r.setTaskId(v.getTaskId());r.setRunId(v.getRunId());r.setTurnId(v.getTurnId());
        r.setSessionId(v.getSessionId());r.setUserId(v.getUserId());r.setTaskType(v.getTaskType());r.setStatus(v.getStatus());
        r.setProgress(v.getProgress());r.setCurrentStage(v.getCurrentStage());r.setRequestJson(v.getRequestJson());r.setAnswer(v.getAnswer());
        r.setCitationsJson(v.getCitationsJson());r.setAttemptCount(v.getAttemptCount());r.setNextRetryAt(time(v.getNextRetryAt()));
        r.setLeaseOwner(v.getLeaseOwner());r.setLeaseUntil(time(v.getLeaseUntil()));r.setErrorCode(v.getErrorCode());r.setErrorMessage(v.getErrorMessage());
        r.setCreatedAt(time(v.getCreatedAt()));r.setUpdatedAt(time(v.getUpdatedAt()));r.setStartedAt(time(v.getStartedAt()));r.setFinishedAt(time(v.getFinishedAt()));return r;
    }
    private AgentTask toDomain(AgentTaskRecord r) {
        AgentTask v=new AgentTask();v.setTaskId(r.getTaskId());v.setRunId(r.getRunId());v.setTurnId(r.getTurnId());v.setSessionId(r.getSessionId());
        v.setUserId(r.getUserId());v.setTaskType(r.getTaskType());v.setStatus(r.getStatus());v.setProgress(r.getProgress());v.setCurrentStage(r.getCurrentStage());
        v.setRequestJson(r.getRequestJson());v.setAnswer(r.getAnswer());v.setCitationsJson(r.getCitationsJson());v.setAttemptCount(r.getAttemptCount());
        v.setNextRetryAt(epoch(r.getNextRetryAt()));v.setLeaseOwner(r.getLeaseOwner());v.setLeaseUntil(epoch(r.getLeaseUntil()));v.setErrorCode(r.getErrorCode());
        v.setErrorMessage(r.getErrorMessage());v.setCreatedAt(epochValue(r.getCreatedAt()));v.setUpdatedAt(epochValue(r.getUpdatedAt()));v.setStartedAt(epoch(r.getStartedAt()));v.setFinishedAt(epoch(r.getFinishedAt()));return v;
    }
    private LocalDateTime time(Long v){return v==null?null:time(v.longValue());}
    private LocalDateTime time(long v){return LocalDateTime.ofInstant(Instant.ofEpochMilli(v),ZoneId.systemDefault());}
    private Long epoch(LocalDateTime v){return v==null?null:v.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();}
    private long epochValue(LocalDateTime v){Long value=epoch(v);return value==null?0:value;}
}
