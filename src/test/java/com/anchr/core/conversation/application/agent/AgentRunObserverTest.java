package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.model.AgentProgressEvent;
import com.anchr.core.conversation.application.model.AgentTokenUsage;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentRunObserverTest {

    @Test
    void publishesExactProgressAndPersistsSignalAssignedTraceOrder() {
        AgentTraceRecorder recorder = mock(AgentTraceRecorder.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        AgentRunObserver observer = new AgentRunObserver(recorder, metrics);
        AgentState state = state();
        List<AgentProgressEvent> events = new ArrayList<>();
        ConversationProgressListener listener = new ConversationProgressListener() {
            @Override public void onAgentProgress(AgentProgressEvent event) { events.add(event); }
        };
        AgentSignal.Trace trace = new AgentSignal.Trace(7, AgentStepType.MODEL_DECISION, 2,
                "stop", Map.of("messageCount", 3), Map.of("toolCallCount", 0),
                new AgentTokenUsage(5, 2), 11, null);

        observer.observe(state, state, List.of(
                new AgentSignal.Progress("agent_thinking", "decision_completed", 2,
                        Map.of("stepOrder", 7, "decision", "FINAL_RESPONSE")),
                trace,
                new AgentSignal.ProtocolError("MISSING_ACTION", "retry", 1, 2, 0)), listener);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.stage()).isEqualTo("agent_thinking");
            assertThat(event.message()).isEqualTo("decision_completed");
            assertThat(event.details()).containsEntry("stepOrder", 7);
        });
        verify(recorder).recordStep(state, 7, AgentStepType.MODEL_DECISION, 2,
                "stop", trace.inputSummary(), trace.outputSummary(), trace.usage(), 11, null);
        assertThat(metrics.get("agent.protocol.error")
                .tags("code", "MISSING_ACTION", "outcome", "retry")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void failedTerminalPreservesLegacyRunResultMetricSemantics() {
        AgentTraceRecorder recorder = mock(AgentTraceRecorder.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        AgentRunObserver observer = new AgentRunObserver(recorder, metrics);
        AgentState state = state();

        observer.observe(state, state,
                List.of(new AgentSignal.Terminal(AgentRunStatus.FAILED, "agent_workflow_failed")),
                ConversationProgressListener.NOOP);

        verify(recorder).finish(state, AgentRunStatus.FAILED,
                "agent_workflow_failed", "agent_workflow_failed");
        assertThat(metrics.find("agent.run.result").tag("status", "FAILED").counter()).isNull();
    }

    private AgentState state() {
        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
        request.setQuery("question");
        AgentRunRequest run = new AgentRunRequest("run", "turn", "session", "user", request);
        return AgentState.initial(run, new AgentBudget(4, 4, 10_000), 0,
                null, false, List.of());
    }
}
