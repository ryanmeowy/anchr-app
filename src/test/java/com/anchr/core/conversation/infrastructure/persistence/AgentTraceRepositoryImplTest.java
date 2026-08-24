package com.anchr.core.conversation.infrastructure.persistence;

import com.anchr.core.conversation.domain.model.AgentRun;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentTraceRepositoryImplTest {

    @Test
    void runWrites_shouldUseDedicatedMapperOperations() {
        AgentTraceMapper mapper = mock(AgentTraceMapper.class);
        AgentTraceRepositoryImpl repository = new AgentTraceRepositoryImpl(mapper);
        AgentRun run = run();
        when(mapper.finishWorkflowRun(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.transitionRun(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("WAITING_TASK")))
                .thenReturn(1);
        when(mapper.markTraditionalFallback("run-1", "traditional_rag_fallback")).thenReturn(1);
        when(mapper.addRunTokenUsage("run-1", 12, 4)).thenReturn(1);

        repository.insertRun(run);

        assertThat(repository.finishWorkflowRun(run)).isTrue();
        assertThat(repository.transitionRun(run, "WAITING_TASK")).isTrue();
        assertThat(repository.markTraditionalFallback("run-1", "traditional_rag_fallback")).isTrue();
        assertThat(repository.addRunTokenUsage("run-1", 12, 4)).isTrue();
        verify(mapper).insertRun(org.mockito.ArgumentMatchers.any());
        verify(mapper).finishWorkflowRun(org.mockito.ArgumentMatchers.any());
        verify(mapper).transitionRun(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("WAITING_TASK"));
    }

    @Test
    void addRunTokenUsage_shouldClampNegativeDeltas() {
        AgentTraceMapper mapper = mock(AgentTraceMapper.class);
        AgentTraceRepositoryImpl repository = new AgentTraceRepositoryImpl(mapper);
        when(mapper.addRunTokenUsage("run-1", 0, 7)).thenReturn(1);

        assertThat(repository.addRunTokenUsage("run-1", -3, 7)).isTrue();

        verify(mapper).addRunTokenUsage("run-1", 0, 7);
    }

    @Test
    void lockRun_shouldReflectWhetherTheRunRowWasLocked() {
        AgentTraceMapper mapper = mock(AgentTraceMapper.class);
        AgentTraceRepositoryImpl repository = new AgentTraceRepositoryImpl(mapper);
        when(mapper.lockRun("run-1")).thenReturn("run-1");
        when(mapper.lockRun("missing")).thenReturn(null);

        assertThat(repository.lockRun("run-1")).isTrue();
        assertThat(repository.lockRun("missing")).isFalse();
        verify(mapper).lockRun("run-1");
        verify(mapper).lockRun("missing");
    }

    @Test
    void lockRun_shouldRejectBlankRunIdsWithoutReadingStorage() {
        AgentTraceMapper mapper = mock(AgentTraceMapper.class);
        AgentTraceRepositoryImpl repository = new AgentTraceRepositoryImpl(mapper);

        assertThat(repository.lockRun(" ")).isFalse();

        verifyNoInteractions(mapper);
    }

    private AgentRun run() {
        AgentRun run = new AgentRun();
        run.setRunId("run-1");
        run.setSessionId("session-1");
        run.setStatus("RUNNING");
        run.setStartedAt(System.currentTimeMillis());
        return run;
    }
}
