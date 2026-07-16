package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.domain.model.AgentRun;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunFinalizerTest {

    @Mock private AgentTraceRepository repository;

    @Test
    void markTurnSaved_shouldCompleteAwaitingRunOnlyAfterTurnPersistence() {
        AgentRun run = awaitingRun(null);
        when(repository.findRun("run-1")).thenReturn(Optional.of(run));
        AgentRunFinalizer finalizer = new AgentRunFinalizer(repository, new SimpleMeterRegistry());

        finalizer.markTurnSaved("run-1");

        ArgumentCaptor<AgentRun> captor = ArgumentCaptor.forClass(AgentRun.class);
        verify(repository).saveRun(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AgentRunStatus.COMPLETED.name());
        assertThat(captor.getValue().getFinishedAt()).isNotNull();
    }

    @Test
    void markTurnSaved_shouldPreserveBudgetOutcomeAsFallback() {
        AgentRun run = awaitingRun("agent_budget_exhausted");
        when(repository.findRun("run-1")).thenReturn(Optional.of(run));
        AgentRunFinalizer finalizer = new AgentRunFinalizer(repository, new SimpleMeterRegistry());

        finalizer.markTurnSaved("run-1");

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.FALLBACK.name());
    }

    @Test
    void markTurnSaved_shouldPreserveProtocolOutcomeAsFallback() {
        AgentRun run = awaitingRun("agent_protocol_error:MISSING_ACTION");
        when(repository.findRun("run-1")).thenReturn(Optional.of(run));
        AgentRunFinalizer finalizer = new AgentRunFinalizer(repository, new SimpleMeterRegistry());

        finalizer.markTurnSaved("run-1");

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.FALLBACK.name());
    }

    @Test
    void markTurnFailed_shouldKeepFailedRunWhenTurnCannotBeSaved() {
        AgentRun run = awaitingRun(null);
        when(repository.findRun("run-1")).thenReturn(Optional.of(run));
        AgentRunFinalizer finalizer = new AgentRunFinalizer(repository, new SimpleMeterRegistry());

        finalizer.markTurnFailed("run-1");

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.FAILED.name());
        assertThat(run.getErrorCode()).isEqualTo("turn_persistence_failed");
    }

    private AgentRun awaitingRun(String fallbackReason) {
        AgentRun run = new AgentRun();
        run.setRunId("run-1");
        run.setStatus(AgentRunStatus.AWAITING_TURN.name());
        run.setFallbackReason(fallbackReason);
        run.setStartedAt(System.currentTimeMillis() - 100L);
        return run;
    }
}
