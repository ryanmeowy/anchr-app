package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AgentMessage;
import com.anchr.core.conversation.application.model.AgentModelResponse;
import com.anchr.core.conversation.application.model.AgentTokenUsage;
import com.anchr.core.conversation.application.model.AgentToolCall;
import com.anchr.core.conversation.application.model.AnswerStatus;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTransitionEngineTest {
    private final AgentTransitionEngine engine = new AgentTransitionEngine();

    @Test
    void sameStateAndEventProduceTheSameTransitionWithoutExternalDependencies() {
        AgentState state = state(100, 10, 10);
        AgentEvent event = new AgentEvent.RunStarted(100);

        AgentTransition first = engine.transition(state, event);
        AgentTransition second = engine.transition(state, event);

        assertThat(first).isEqualTo(second);
        assertThat(first.command()).isInstanceOf(AgentCommand.CallModel.class);
        assertThat(first.nextState().stepCount()).isEqualTo(1);
        assertThat(state.stepCount()).isZero();
    }

    @Test
    void multipleToolCallsAreQueuedAndDispatchedOneAtATime() {
        AgentState planning = engine.transition(state(100, 10, 10),
                new AgentEvent.RunStarted(100)).nextState();
        AgentToolCall first = new AgentToolCall("call-1", "search_knowledge", "{}");
        AgentToolCall second = new AgentToolCall("call-2", "read_document", "{}");
        AgentModelResponse response = new AgentModelResponse(null, List.of(first, second),
                new AgentTokenUsage(3, 2), "model", "tool_calls", "request");

        AgentTransition selected = engine.transition(planning, new AgentEvent.ModelCompleted(
                response, new AgentModelDecision.ToolCalls(List.of(first, second)), 12, 112));

        assertThat(selected.command()).isEqualTo(new AgentCommand.CallTool(first, 1, 2, 112));
        assertThat(selected.nextState().pendingToolCalls()).containsExactly(second);
        assertThat(selected.nextState().toolCallCount()).isEqualTo(1);

        AgentToolResult result = AgentToolResult.success("{}", List.of(evidence("seg-1")));
        AgentTransition completed = engine.transition(selected.nextState(),
                new AgentEvent.ToolCompleted(first, result, "{}", 1, 8, 120));

        assertThat(completed.command()).isEqualTo(new AgentCommand.CallTool(second, 2, 3, 120));
        assertThat(completed.nextState().evidence()).containsOnlyKeys("seg-1");
        assertThat(completed.nextState().toolCallCount()).isEqualTo(2);
    }

    @Test
    void finalAnswerFromToolDiscardsRemainingPendingCallsBeforeValidation() {
        AgentToolCall first = new AgentToolCall("call-1", "deliver_answer", "{}");
        AgentToolCall second = new AgentToolCall("call-2", "search_knowledge", "{}");
        AgentState selected = state(100, 10, 10)
                .withToolCalls(AgentMessage.assistantToolCalls(null, List.of(first, second)),
                        List.of(first, second));
        AgentTransition scheduled = engine.transition(selected,
                new AgentEvent.ModelCompleted(new AgentModelResponse(null, List.of(first, second),
                        AgentTokenUsage.EMPTY, "model", "tool_calls", "request"),
                        new AgentModelDecision.ToolCalls(List.of(first, second)), 1, 101));
        AgentToolResult result = AgentToolResult.finalAnswer(
                new AgentFinalAnswer(AgentAnswerType.CHAT, "done", List.of()));

        AgentTransition completed = engine.transition(scheduled.nextState(),
                new AgentEvent.ToolCompleted(first, result, "{}", 1, 1, 102));

        assertThat(completed.command()).isInstanceOf(AgentCommand.VerifyAnswer.class);
        assertThat(completed.nextState().pendingToolCalls()).isEmpty();
    }

    @Test
    void duplicateToolDoesNotConsumeToolBudgetOrCreateFailureTrace() {
        AgentToolCall call = new AgentToolCall("same", "search_knowledge", "{}");
        AgentState state = state(100, 10, 10)
                .markToolCall(call)
                .withToolCalls(AgentMessage.assistantToolCalls(null, List.of(call)), List.of(call));

        AgentTransition transition = engine.transition(state,
                new AgentEvent.ModelCompleted(new AgentModelResponse(null, List.of(call),
                        AgentTokenUsage.EMPTY, "model", "tool_calls", "request"),
                        new AgentModelDecision.ToolCalls(List.of(call)), 1, 101));

        assertThat(transition.nextState().toolCallCount()).isEqualTo(1);
        assertThat(transition.signals()).anySatisfy(signal -> {
            assertThat(signal).isInstanceOf(AgentSignal.Progress.class);
            AgentSignal.Progress progress = (AgentSignal.Progress) signal;
            assertThat(progress.message()).isEqualTo("duplicate_rejected");
        });
        assertThat(transition.signals()).noneMatch(signal -> signal instanceof AgentSignal.Trace trace
                && trace.errorCode() != null);
    }

    @Test
    void exhaustedBudgetTakesPrecedenceOverDuplicateGuard() {
        AgentToolCall call = new AgentToolCall("same", "search_knowledge", "{}");
        AgentState exhausted = state(100, 10, 1)
                .markToolCall(call)
                .withToolCalls(AgentMessage.assistantToolCalls(null, List.of(call)), List.of(call));

        AgentTransition transition = engine.transition(exhausted,
                new AgentEvent.ModelCompleted(new AgentModelResponse(null, List.of(call),
                        AgentTokenUsage.EMPTY, "model", "tool_calls", "request"),
                        new AgentModelDecision.ToolCalls(List.of(call)), 1, 101));

        assertThat(transition.terminal()).isNotNull();
        assertThat(transition.terminal().fallbackReason()).isEqualTo("agent_budget_exhausted");
        assertThat(transition.signals()).noneMatch(signal -> signal instanceof AgentSignal.Progress progress
                && "duplicate_rejected".equals(progress.message()));
    }

    @Test
    void exhaustedBudgetTakesPrecedenceOverReadLimitGuard() {
        AgentToolCall first = new AgentToolCall("read-1", "read_document", "{}");
        AgentToolCall second = new AgentToolCall("read-2", "read_document", "{}");
        AgentToolCall pending = new AgentToolCall("read-3", "read_document", "{}");
        AgentState exhausted = state(100, 10, 2)
                .markToolCall(first)
                .markToolCall(second)
                .registerEvidence(List.of(evidence("seg-1")))
                .withToolCalls(AgentMessage.assistantToolCalls(null, List.of(pending)), List.of(pending));

        AgentTransition transition = engine.transition(exhausted,
                new AgentEvent.ModelCompleted(new AgentModelResponse(null, List.of(pending),
                        AgentTokenUsage.EMPTY, "model", "tool_calls", "request"),
                        new AgentModelDecision.ToolCalls(List.of(pending)), 1, 101));

        assertThat(transition.command()).isInstanceOf(AgentCommand.CallEvidenceFinalizer.class);
        assertThat(transition.nextState().finalizerTrigger()).isEqualTo("agent_budget_exhausted");
        assertThat(transition.signals()).noneMatch(signal -> signal instanceof AgentSignal.Progress progress
                && "read_limit_reached".equals(progress.message()));
        assertThat(transition.signals()).noneMatch(signal -> signal instanceof AgentSignal.Trace trace
                && "READ_LIMIT_REACHED".equals(trace.decision()));
    }

    @Test
    void protocolErrorRetriesInJsonThenFallsBackAtExistingThreshold() {
        AgentState planning = engine.transition(state(100, 10, 10),
                new AgentEvent.RunStarted(100)).nextState();
        AgentModelResponse raw = new AgentModelResponse("raw", List.of(), AgentTokenUsage.EMPTY,
                "model", "stop", "request");
        AgentTransition first = engine.transition(planning, new AgentEvent.ModelCompleted(raw,
                new AgentModelDecision.ProtocolError("MISSING_ACTION"), 1, 101));

        assertThat(first.command()).isInstanceOf(AgentCommand.CallModel.class);
        assertThat(((AgentCommand.CallModel) first.command()).toolCallMode()).isEqualTo("JSON");
        assertThat(first.nextState().consecutiveProtocolErrors()).isEqualTo(1);

        AgentTransition second = engine.transition(first.nextState(), new AgentEvent.ModelCompleted(raw,
                new AgentModelDecision.ProtocolError("MISSING_ACTION"), 1, 102));
        assertThat(second.terminal()).isNotNull();
        assertThat(second.command()).isNull();
        assertThat(second.terminal().fallbackReason()).isEqualTo("agent_protocol_error:MISSING_ACTION");
    }

    @Test
    void terminalStateCannotProduceAnotherCommand() {
        AgentTransition cancelled = engine.transition(state(100, 10, 10),
                new AgentEvent.CancellationRequested(100));

        assertThat(cancelled.command()).isNull();
        assertThat(cancelled.terminal()).isNotNull();
        assertThatThrownBy(() -> engine.transition(cancelled.nextState(),
                new AgentEvent.RunStarted(101))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void verifierFailureTerminatesRunInsteadOfBecomingAnswerRejection() {
        IllegalStateException failure = new IllegalStateException("verifier unavailable");

        AgentTransition transition = engine.transition(state(100, 10, 10),
                new AgentEvent.AnswerVerificationFailed(failure, 101));

        assertThat(transition.command()).isNull();
        assertThat(transition.nextState().phase()).isEqualTo(AgentWorkflowPhase.FAILED);
        assertThat(transition.terminal().runStatus()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(transition.terminal().fallbackReason()).isEqualTo("agent_workflow_failed");
        assertThat(transition.terminal().cause()).isSameAs(failure);
        assertThat(transition.signals()).containsExactly(
                new AgentSignal.Terminal(AgentRunStatus.FAILED, "agent_workflow_failed"));
    }

    @Test
    void toolEffectFailureTerminatesWithoutAddingLegacyIncompatibleStepOrProgress() {
        AgentToolCall call = new AgentToolCall("call-1", "search_knowledge", "{}");
        IllegalStateException failure = new IllegalStateException("tool effect failed");
        AgentState state = state(100, 10, 10);

        AgentTransition transition = engine.transition(state,
                new AgentEvent.ToolFailed(call, failure, 1, 12, 112));

        assertThat(transition.command()).isNull();
        assertThat(transition.nextState().phase()).isEqualTo(AgentWorkflowPhase.FAILED);
        assertThat(transition.nextState().traceOrder()).isEqualTo(state.traceOrder());
        assertThat(transition.terminal().cause()).isSameAs(failure);
        assertThat(transition.signals()).containsExactly(
                new AgentSignal.Terminal(AgentRunStatus.FAILED, "agent_workflow_failed"));
    }

    @Test
    void finalizerFailurePreservesCauseForObserverLogging() {
        IllegalStateException failure = new IllegalStateException("finalizer unavailable");
        AgentState finalizing = state(100, 10, 10)
                .registerEvidence(List.of(evidence("seg-1")))
                .beginFinalizer("test");

        AgentTransition transition = engine.transition(finalizing,
                new AgentEvent.FinalizerModelFailed(failure, 12, 112));

        assertThat(transition.signals()).anySatisfy(signal -> {
            assertThat(signal).isEqualTo(
                    new AgentSignal.EffectFailure("EVIDENCE_FINALIZATION", failure));
        });
    }

    @Test
    void presentationFailurePreservesCauseForObserverLogging() {
        IllegalStateException failure = new IllegalStateException("stream unavailable");
        PresentedAgentAnswer fallback = new PresentedAgentAnswer(
                "已验证草稿", AnswerStatus.ANSWERED, null, List.of());

        AgentTransition transition = engine.transition(state(100, 10, 10).beginPresentation(true),
                new AgentEvent.PresentationFailed(fallback, failure, true, -1, 12, 112));

        assertThat(transition.signals()).anySatisfy(signal -> {
            assertThat(signal).isEqualTo(
                    new AgentSignal.EffectFailure("FINAL_PRESENTATION", failure));
        });
        assertThat(transition.terminal().answer()).isEqualTo("已验证草稿");
    }

    private AgentState state(long now, int maxSteps, int maxTools) {
        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
        request.setQuery("问题");
        request.setKbIds(List.of("kb-1"));
        request.setAssetIdList(List.of());
        AgentRunRequest run = new AgentRunRequest("run", "turn", "session", "user", request);
        AgentRuntimeSettings settings = new AgentRuntimeSettings(true,
                AgentRuntimeSettings.ToolCallMode.AUTO, AgentRuntimeSettings.NativeToolChoice.REQUIRED,
                true, maxSteps, maxTools, Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofMinutes(10), Duration.ofSeconds(90), 2, Duration.ofMinutes(35),
                3, 500, 500_000, 12_000);
        return AgentState.initial(run, new AgentBudget(maxSteps, maxTools, now + 10_000), now,
                settings, false, List.of(AgentMessage.system("system"), AgentMessage.user("问题")));
    }

    private ConversationRetrievalCandidate evidence(String segmentId) {
        return ConversationRetrievalCandidate.builder().segmentId(segmentId).assetId("asset")
                .content("evidence").build();
    }
}
