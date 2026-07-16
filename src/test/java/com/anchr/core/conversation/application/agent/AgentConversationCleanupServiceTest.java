package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.domain.model.AgentTaskStatus;
import com.anchr.core.conversation.domain.repository.AgentTaskRepository;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentConversationCleanupServiceTest {
    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTraceRepository traceRepository;
    @Mock private AgentTaskProcessor taskProcessor;
    @Mock private AgentRunCancellationRegistry runCancellationRegistry;

    @Test
    void cancelRunning_shouldInterruptOnlyActiveTasksAndAllSessionRuns() {
        AgentTask pending = task("task-pending", AgentTaskStatus.PENDING);
        AgentTask running = task("task-running", AgentTaskStatus.RUNNING);
        AgentTask completed = task("task-completed", AgentTaskStatus.SUCCEEDED);
        when(taskRepository.findBySessionId("session-1"))
                .thenReturn(List.of(pending, running, completed));
        AgentConversationCleanupService service = service();

        service.cancelRunning("session-1");

        verify(taskProcessor).interrupt("task-pending");
        verify(taskProcessor).interrupt("task-running");
        verify(taskProcessor, never()).interrupt("task-completed");
        verify(runCancellationRegistry).cancelBySessionId("session-1");
    }

    @Test
    void deleteRecords_shouldDeleteTasksBeforeRuns() {
        AgentConversationCleanupService service = service();

        service.deleteRecords("session-1");

        InOrder order = inOrder(taskRepository, traceRepository);
        order.verify(taskRepository).deleteBySessionId("session-1");
        order.verify(traceRepository).deleteBySessionId("session-1");
    }

    private AgentConversationCleanupService service() {
        return new AgentConversationCleanupService(
                taskRepository, traceRepository, taskProcessor, runCancellationRegistry);
    }

    private AgentTask task(String id, AgentTaskStatus status) {
        AgentTask task = new AgentTask();
        task.setTaskId(id);
        task.setStatus(status.name());
        return task;
    }
}
