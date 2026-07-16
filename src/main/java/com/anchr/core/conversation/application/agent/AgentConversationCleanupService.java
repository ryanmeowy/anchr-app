package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.domain.model.AgentTaskStatus;
import com.anchr.core.conversation.domain.repository.AgentTaskRepository;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * Stops in-flight Agent work and removes Agent-owned records for a deleted conversation.
 */
@Service
@RequiredArgsConstructor
public class AgentConversationCleanupService {
    private static final Set<String> ACTIVE_TASK_STATUSES = Set.of(
            AgentTaskStatus.PENDING.name(),
            AgentTaskStatus.RUNNING.name()
    );

    private final AgentTaskRepository taskRepository;
    private final AgentTraceRepository traceRepository;
    private final AgentTaskProcessor taskProcessor;
    private final AgentRunCancellationRegistry runCancellationRegistry;

    public void cancelRunning(String sessionId) {
        if (!StringUtils.hasText(sessionId)) return;
        taskRepository.findBySessionId(sessionId).stream()
                .filter(task -> ACTIVE_TASK_STATUSES.contains(task.getStatus()))
                .forEach(task -> taskProcessor.interrupt(task.getTaskId()));
        runCancellationRegistry.cancelBySessionId(sessionId);
    }

    public void deleteRecords(String sessionId) {
        if (!StringUtils.hasText(sessionId)) return;
        taskRepository.deleteBySessionId(sessionId);
        traceRepository.deleteBySessionId(sessionId);
    }
}
