package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.AgentRuntimeSnapshotService;
import com.anchr.core.conversation.application.AnswerEventPublisher;
import com.anchr.core.conversation.application.AnswerIdentity;
import com.anchr.core.conversation.application.ConversationCitationReasonEnricher;
import com.anchr.core.conversation.application.acl.ConversationKnowledgeAcl;
import com.anchr.core.conversation.application.acl.ConversationRetrievalAcl;
import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.domain.model.AgentRun;
import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.anchr.core.conversation.domain.model.ConversationCitation;
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
    private final AnswerEventPublisher eventPublisher = mock(AnswerEventPublisher.class);
    private final AgentRuntimeSnapshotService snapshotService = mock(AgentRuntimeSnapshotService.class);
    private final ConversationCitationReasonEnricher citationReasonEnricher =
            mock(ConversationCitationReasonEnricher.class);
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
                eventPublisher,
                Runnable::run,
                snapshotService,
                new AgentCitationPolicy(),
                citationReasonEnricher);
    }

    @Test
    void successPersistsTaskAndTurnBeforeRunSnapshotAndSse() {
        AgentTask task = task();
        when(taskRepository.saveClaimed(task, owner())).thenReturn(true);

        ReflectionTestUtils.invokeMethod(
                processor, "complete", task, "answer", "[]", List.of(), 3, 1);

        InOrder order = inOrder(taskRepository, conversationRepository, traceRepository,
                snapshotService, eventPublisher);
        order.verify(taskRepository).saveClaimed(task, owner());
        order.verify(conversationRepository).saveTurn(any(ConversationTurn.class));
        order.verify(traceRepository).saveRun(any(AgentRun.class));
        order.verify(snapshotService).publishTask("run-1", task);
        AnswerIdentity identity = AnswerIdentity.forTask(task);
        order.verify(eventPublisher).progress(identity, "COMPLETED", 100);
        order.verify(eventPublisher).snapshot(identity, "answer");
        order.verify(eventPublisher).citations(identity, List.of());
        order.verify(eventPublisher).completed(identity);
    }

    @Test
    void terminalFailureResetsThenPersistsBeforeRunSnapshotAndSse() {
        AgentTask task = task();
        when(taskRepository.saveClaimed(task, owner())).thenReturn(true);

        ReflectionTestUtils.invokeMethod(processor, "fail", task, "FAILED_CODE", "failed", false);

        InOrder order = inOrder(eventPublisher, taskRepository, conversationRepository,
                traceRepository, snapshotService);
        AnswerIdentity identity = AnswerIdentity.forTask(task);
        order.verify(eventPublisher).snapshot(identity, "");
        order.verify(taskRepository).saveClaimed(task, owner());
        order.verify(conversationRepository).saveTurn(any(ConversationTurn.class));
        order.verify(traceRepository).saveRun(any(AgentRun.class));
        order.verify(snapshotService).publishTask("run-1", task);
        order.verify(eventPublisher).progress(identity, "FAILED", 100);
        order.verify(eventPublisher).citations(identity, List.of());
        order.verify(eventPublisher).failed(identity, "FAILED_CODE");
    }

    @Test
    void retryOnlyPersistsAndPublishesTheRetryTask() {
        AgentTask task = task();

        ReflectionTestUtils.invokeMethod(processor, "fail", task, "RETRY_CODE", "retry", true);

        InOrder order = inOrder(eventPublisher, taskRepository);
        AnswerIdentity identity = AnswerIdentity.forTask(task);
        order.verify(eventPublisher).snapshot(identity, "");
        order.verify(taskRepository).saveClaimed(task, owner());
        order.verify(eventPublisher).progress(identity, "RETRY_WAIT", task.getProgress());
        verify(conversationRepository, never()).saveTurn(any());
        verify(traceRepository, never()).saveRun(any());
        verify(snapshotService, never()).publishTask(any(), any());
        verify(eventPublisher, never()).completed(any());
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
        verify(eventPublisher, never()).completed(any());
    }

    @Test
    void asyncSummaryEnrichesReasonsOutsideWorkflowUsingPersistedTurnQuestion() {
        AgentTask task = task();
        ConversationTurn turn = turn();
        turn.setQuery("用户的原始总结问题");
        turn.setRewrittenQuery("改写后的总结问题");
        when(conversationRepository.findTurn("session-1", "turn-1"))
                .thenReturn(Optional.of(turn));
        Object request = ReflectionTestUtils.invokeMethod(
                processor,
                "parseRequest",
                "{\"assets\":[],\"instruction\":\"工具生成的总结指令\",\"language\":\"中文\"}");
        List<ConversationCitation> citations = List.of(new ConversationCitation());

        ReflectionTestUtils.invokeMethod(
                processor, "enrichCitationReasons", task, request, "最终总结", citations);

        verify(citationReasonEnricher).enrich(
                "用户的原始总结问题", "改写后的总结问题", "最终总结", citations);
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
