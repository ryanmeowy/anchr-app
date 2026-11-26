package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunStaleSweeperTest {

    @Mock
    private AgentTraceRepository repository;

    @Test
    void sweepShouldFailRunsOlderThanTheThreshold() {
        when(repository.failStaleAwaitingRuns(eq(10), eq(100))).thenReturn(2);

        new AgentRunStaleSweeper(repository).sweep();

        verify(repository).failStaleAwaitingRuns(10, 100);
    }

    @Test
    void sweepShouldNotFailTheSchedulerWhenTheRepositoryThrows() {
        when(repository.failStaleAwaitingRuns(eq(10), eq(100)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(() -> new AgentRunStaleSweeper(repository).sweep())
                .doesNotThrowAnyException();
    }
}
