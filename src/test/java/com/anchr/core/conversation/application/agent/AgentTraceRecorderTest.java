package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.domain.model.AgentRun;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTraceRecorderTest {

    private final AgentTraceRepository traceRepository = mock(AgentTraceRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private AgentTraceRecorder recorder;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        recorder = new AgentTraceRecorder(
                traceRepository,
                conversationRepository,
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                transactionTemplate);
    }

    @Test
    void start_shouldLockActiveSessionBeforeSingleTableInsert() {
        when(conversationRepository.lockActiveSession("session-1")).thenReturn(true);

        recorder.start(state());

        ArgumentCaptor<AgentRun> run = ArgumentCaptor.forClass(AgentRun.class);
        verify(conversationRepository).lockActiveSession("session-1");
        verify(traceRepository).insertRun(run.capture());
        assertThat(run.getValue().getRunId()).isEqualTo("run-1");
        assertThat(run.getValue().getStatus()).isEqualTo(AgentRunStatus.RUNNING.name());
    }

    @Test
    void start_shouldSkipInsertWhenSessionIsMissingOrDeleted() {
        when(conversationRepository.lockActiveSession("session-1")).thenReturn(false);

        recorder.start(state());

        verify(traceRepository, never()).insertRun(any());
    }

    @Test
    void finish_shouldPersistSyncSnapshotOnlyFromRunning() {
        when(traceRepository.finishWorkflowRun(any())).thenReturn(true);

        recorder.finish(state(), AgentRunStatus.WAITING_TASK, null, null);

        ArgumentCaptor<AgentRun> run = ArgumentCaptor.forClass(AgentRun.class);
        verify(traceRepository).finishWorkflowRun(run.capture());
        assertThat(run.getValue().getStatus()).isEqualTo(AgentRunStatus.WAITING_TASK.name());
        assertThat(run.getValue().getFinishedAt()).isNull();
    }

    @Test
    void finish_shouldAwaitTraditionalFallbackInsteadOfPersistingPrematureFailure() {
        AgentRuntimeSettings settings = mock(AgentRuntimeSettings.class);
        when(settings.fallbackToTraditional()).thenReturn(true);
        when(traceRepository.finishWorkflowRun(any())).thenReturn(true);

        recorder.finish(state(settings), AgentRunStatus.FAILED,
                "agent_workflow_failed", "agent_workflow_failed");

        ArgumentCaptor<AgentRun> run = ArgumentCaptor.forClass(AgentRun.class);
        verify(traceRepository).finishWorkflowRun(run.capture());
        assertThat(run.getValue().getStatus()).isEqualTo(AgentRunStatus.AWAITING_TURN.name());
        assertThat(run.getValue().getFinishedAt()).isNull();
    }

    private AgentState state() {
        return state(null);
    }

    private AgentState state(AgentRuntimeSettings settings) {
        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
        request.setQuery("question");
        AgentRunRequest run = new AgentRunRequest("run-1", "turn-1", "session-1", "user-1", request);
        return AgentState.initial(run, new AgentBudget(4, 4, System.currentTimeMillis() + 10_000),
                System.currentTimeMillis() - 100, settings, false, List.of());
    }
}
