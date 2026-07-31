package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.AgentRuntimeSnapshotService;
import com.anchr.core.conversation.application.acl.ConversationKnowledgeAcl;
import com.anchr.core.conversation.application.acl.ConversationRetrievalAcl;
import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.domain.model.AgentRun;
import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.anchr.core.conversation.domain.port.ConversationGenerationPort;
import com.anchr.core.conversation.domain.repository.AgentTaskRepository;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.testsupport.RuntimeConfigTestUnits;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentTaskProcessorTerminalOrderingTest {

    private final AgentTaskRepository taskRepository = mock(AgentTaskRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final AgentTraceRepository traceRepository = mock(AgentTraceRepository.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final AgentTaskStreamService streamService = mock(AgentTaskStreamService.class);
    private final AgentRuntimeSnapshotService snapshotService = mock(AgentRuntimeSnapshotService.class);
    private AgentTaskProcessor processor;

    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(traceRepository.findSteps("run-1")).thenReturn(List.of());
        when(traceRepository.findRun("run-1")).thenReturn(Optional.of(run()));
        when(conversationRepository.findTurn("session-1", "turn-1"))
                .thenReturn(Optional.of(turn()));

        processor = new AgentTaskProcessor(
                taskRepository,
                conversationRepository,
                traceRepository,
                mock(ConversationKnowledgeAcl.class),
                mock(ConversationRetrievalAcl.class),
                mock(ConversationGenerationPort.class),
                mock(ConversationCitationMapper.class),
                mock(ConversationTurnCodec.class),
                new ObjectMapper(),
                RuntimeConfigTestUnits.defaults(),
                transactionTemplate,
                streamService,
                Runnable::run,
                snapshotService,
                new AgentCitationPolicy());
    }

    @Test
    void successPersistsTaskAndTurnBeforeRunSnapshotAndSse() {
        AgentTask task = task();
        when(taskRepository.saveClaimed(task, owner())).thenReturn(true);

        ReflectionTestUtils.invokeMethod(processor, "complete", task, "answer", "[]", 3, 1);

        InOrder order = inOrder(taskRepository, conversationRepository, traceRepository,
                snapshotService, streamService);
        order.verify(taskRepository).saveClaimed(task, owner());
        order.verify(conversationRepository).saveTurn(any(ConversationTurn.class));
        order.verify(traceRepository).saveRun(any(AgentRun.class));
        order.verify(snapshotService).publishTask("run-1", task);
        order.verify(streamService).complete(task);
    }

    @Test
    void terminalFailureResetsThenPersistsBeforeRunSnapshotAndSse() {
        AgentTask task = task();
        when(taskRepository.saveClaimed(task, owner())).thenReturn(true);

        ReflectionTestUtils.invokeMethod(processor, "fail", task, "FAILED_CODE", "failed", false);

        InOrder order = inOrder(streamService, taskRepository, conversationRepository,
                traceRepository, snapshotService);
        order.verify(streamService).publishReset("task-1", "");
        order.verify(taskRepository).saveClaimed(task, owner());
        order.verify(conversationRepository).saveTurn(any(ConversationTurn.class));
        order.verify(traceRepository).saveRun(any(AgentRun.class));
        order.verify(snapshotService).publishTask("run-1", task);
        order.verify(streamService).complete(task);
    }

    @Test
    void retryOnlyPersistsAndPublishesTheRetryTask() {
        AgentTask task = task();

        ReflectionTestUtils.invokeMethod(processor, "fail", task, "RETRY_CODE", "retry", true);

        InOrder order = inOrder(streamService, taskRepository);
        order.verify(streamService).publishReset("task-1", "");
        order.verify(taskRepository).saveClaimed(task, owner());
        order.verify(streamService).publishTask(task);
        verify(conversationRepository, never()).saveTurn(any());
        verify(traceRepository, never()).saveRun(any());
        verify(snapshotService, never()).publishTask(any(), any());
        verify(streamService, never()).complete(any(AgentTask.class));
    }

    @Test
    void successStopsPublishingWhenTheClaimIsLost() {
        AgentTask task = task();
        when(taskRepository.saveClaimed(task, owner())).thenReturn(false);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                processor, "complete", task, "answer", "[]", 3, 1))
                .isInstanceOf(RuntimeException.class);

        verify(conversationRepository, never()).saveTurn(any());
        verify(traceRepository, never()).saveRun(any());
        verify(snapshotService, never()).publishTask(any(), any());
        verify(streamService, never()).complete(any(AgentTask.class));
    }

    private AgentTask task() {
        AgentTask task = new AgentTask();
        task.setTaskId("task-1");
        task.setRunId("run-1");
        task.setSessionId("session-1");
        task.setTurnId("turn-1");
        task.setStatus("RUNNING");
        task.setCurrentStage("FINALIZING");
        task.setProgress(90);
        task.setAttemptCount(1);
        task.setRequestJson("{\"assets\":[]}");
        return task;
    }

    private AgentRun run() {
        AgentRun run = new AgentRun();
        run.setRunId("run-1");
        run.setStartedAt(System.currentTimeMillis() - 1_000);
        return run;
    }

    private ConversationTurn turn() {
        ConversationTurn turn = new ConversationTurn();
        turn.setSessionId("session-1");
        turn.setTurnId("turn-1");
        return turn;
    }

    private String owner() {
        return (String) ReflectionTestUtils.getField(processor, "owner");
    }
}
