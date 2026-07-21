package com.anchr.core.conversation.application;

import com.anchr.core.conversation.application.agent.AgentTaskProcessor;
import com.anchr.core.conversation.application.agent.AgentTaskStreamService;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.anchr.core.conversation.domain.repository.AgentTaskRepository;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentTaskQueryServiceTest {

    @Test
    void cancel_shouldPersistCancelledTurnAndInterruptRunningWorker() {
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        AgentTraceRepository traces = mock(AgentTraceRepository.class);
        AgentTaskProcessor processor = mock(AgentTaskProcessor.class);
        AgentTaskStreamService taskStreams = mock(AgentTaskStreamService.class);
        ConversationTurnCodec codec = mock(ConversationTurnCodec.class);
        TransactionTemplate transactions = immediateTransactions();
        AgentTaskQueryService service = new AgentTaskQueryService(
                tasks, conversations, traces, processor, taskStreams, codec, transactions);
        AgentTask running = task("RUNNING");
        AgentTask cancelled = task("CANCELLED");
        cancelled.setAnswer("任务已取消。");
        cancelled.setProgress(100);
        cancelled.setCurrentStage("CANCELLED");
        when(tasks.findById("task-1")).thenReturn(Optional.of(running), Optional.of(cancelled));
        when(tasks.cancel(eq("task-1"), eq("single_user"), anyLong())).thenReturn(true);
        when(conversations.findSession("session-1")).thenReturn(Optional.of(session()));
        ConversationTurn turn = new ConversationTurn();
        turn.setTurnId("turn-1");
        turn.setSessionId("session-1");
        when(conversations.findTurn("session-1", "turn-1")).thenReturn(Optional.of(turn));
        when(codec.parseCitations(any())).thenReturn(List.of());

        var result = service.cancel("task-1");

        assertThat(result.getStatus()).isEqualTo("CANCELLED");
        assertThat(turn.getAnswerStatus()).isEqualTo("CANCELLED");
        assertThat(turn.getAnswer()).isEqualTo("任务已取消。");
        verify(conversations).saveTurn(turn);
        verify(processor).recordCancellation(running);
        verify(processor).interrupt("task-1");
        verify(taskStreams).complete(result);
    }

    @Test
    void cancel_shouldBeIdempotentForTerminalTask() {
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        AgentTaskQueryService service = new AgentTaskQueryService(tasks, conversations,
                mock(AgentTraceRepository.class), mock(AgentTaskProcessor.class),
                mock(AgentTaskStreamService.class),
                mock(ConversationTurnCodec.class), mock(TransactionTemplate.class));
        when(tasks.findById("task-1")).thenReturn(Optional.of(task("CANCELLED")));
        when(conversations.findSession("session-1")).thenReturn(Optional.of(session()));

        assertThat(service.cancel("task-1").getStatus()).isEqualTo("CANCELLED");
        verify(tasks, never()).cancel(anyString(), anyString(), anyLong());
    }

    @SuppressWarnings("unchecked")
    private TransactionTemplate immediateTransactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return template;
    }

    private AgentTask task(String status) {
        AgentTask task = new AgentTask();
        task.setTaskId("task-1");
        task.setRunId("run-1");
        task.setTurnId("turn-1");
        task.setSessionId("session-1");
        task.setUserId("single_user");
        task.setTaskType("DOCUMENT_SUMMARY");
        task.setStatus(status);
        return task;
    }

    private ConversationSession session() {
        ConversationSession session = new ConversationSession();
        session.setSessionId("session-1");
        session.setUserId("single_user");
        return session;
    }
}
