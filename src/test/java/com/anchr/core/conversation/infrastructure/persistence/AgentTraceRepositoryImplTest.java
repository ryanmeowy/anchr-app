package com.anchr.core.conversation.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentTraceRepositoryImplTest {

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
}
