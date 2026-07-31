package com.anchr.core.conversation.application.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentWorkflowPhaseTest {

    @Test
    void allowsPlanningToolAndValidationLoopsBeforeCompletion() {
        AgentRunState state = state();

        state.transitionTo(AgentWorkflowPhase.TOOL_EXECUTION);
        state.transitionTo(AgentWorkflowPhase.PLANNING);
        state.transitionTo(AgentWorkflowPhase.EVIDENCE_VALIDATION);
        state.transitionTo(AgentWorkflowPhase.PLANNING);
        state.transitionTo(AgentWorkflowPhase.FINALIZING);
        state.transitionTo(AgentWorkflowPhase.COMPLETED);

        assertThat(state.getPhase()).isEqualTo(AgentWorkflowPhase.COMPLETED);
    }

    @Test
    void rejectsIllegalAndPostTerminalTransitions() {
        AgentRunState state = state();
        state.transitionTo(AgentWorkflowPhase.EVIDENCE_VALIDATION);

        assertThatThrownBy(() -> state.transitionTo(AgentWorkflowPhase.TOOL_EXECUTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVIDENCE_VALIDATION -> TOOL_EXECUTION");

        state.transitionTo(AgentWorkflowPhase.COMPLETED);
        assertThatThrownBy(() -> state.transitionTo(AgentWorkflowPhase.PLANNING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED -> PLANNING");
    }

    private AgentRunState state() {
        return new AgentRunState(null,
                new AgentBudget(6, 4, System.currentTimeMillis() + 10_000L),
                System.currentTimeMillis());
    }
}
