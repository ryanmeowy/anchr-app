package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.domain.model.AgentTask;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;

class AgentTaskStreamServiceTest {

    @Test
    void taskProgressEventDoesNotExposeAnswerSnapshot() {
        AgentTask task = completedTask();

        Map<String, Object> event = AgentTaskStreamService.taskEvent(task);

        assertThat(event).containsEntry("status", "SUCCEEDED");
        assertThat(event).doesNotContainKey("answer");
    }

    @Test
    void completionPublishesMetadataBeforeCanonicalAnswer() {
        AgentTaskStreamService service = spy(new AgentTaskStreamService());
        AgentTask task = completedTask();

        service.complete(task);

        InOrder order = inOrder(service);
        order.verify(service).publishTask(task);
        order.verify(service).publishReset("task-1", "最终回答");
    }

    private AgentTask completedTask() {
        AgentTask task = new AgentTask();
        task.setTaskId("task-1");
        task.setTaskType("DOCUMENT_SUMMARY");
        task.setStatus("SUCCEEDED");
        task.setProgress(100);
        task.setCurrentStage("COMPLETED");
        task.setAnswer("最终回答");
        return task;
    }
}
