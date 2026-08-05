package com.anchr.core.conversation.application.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentWorkflowPhaseTest {

    @Test
    void phaseGraphAllowsPlanningToolValidationAndFinalizationPath() {
        assertThat(AgentWorkflowPhase.PLANNING.canTransitionTo(AgentWorkflowPhase.TOOL_EXECUTION)).isTrue();
        assertThat(AgentWorkflowPhase.TOOL_EXECUTION.canTransitionTo(AgentWorkflowPhase.PLANNING)).isTrue();
        assertThat(AgentWorkflowPhase.PLANNING.canTransitionTo(AgentWorkflowPhase.EVIDENCE_VALIDATION)).isTrue();
        assertThat(AgentWorkflowPhase.EVIDENCE_VALIDATION.canTransitionTo(AgentWorkflowPhase.FINALIZING)).isTrue();
        assertThat(AgentWorkflowPhase.FINALIZING.canTransitionTo(AgentWorkflowPhase.COMPLETED)).isTrue();
    }

    @Test
    void transitionEngineRejectsEventsAfterTerminal() {
        long now = 10L;
        AgentState initial = AgentState.initial(null, new AgentBudget(6, 4, 1_000), now,
                null, false, List.of());
        AgentTransition cancelled = new AgentTransitionEngine().transition(initial,
                new AgentEvent.CancellationRequested(now));

        assertThat(cancelled.nextState().phase()).isEqualTo(AgentWorkflowPhase.CANCELLED);
        assertThatThrownBy(() -> new AgentTransitionEngine().transition(cancelled.nextState(),
                new AgentEvent.RunStarted(now + 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Terminal Agent state");
    }

    @Test
    void stateSnapshotRejectsIllegalPhaseTransition() {
        AgentState finalizing = AgentState.initial(null, new AgentBudget(6, 4, 1_000),
                10, null, false, List.of()).beginFinalizer("test");

        assertThatThrownBy(() -> finalizing.withPhase(
                AgentWorkflowPhase.PLANNING, AgentStepType.MODEL_DECISION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Illegal Agent phase transition: FINALIZING -> PLANNING");
    }
}
