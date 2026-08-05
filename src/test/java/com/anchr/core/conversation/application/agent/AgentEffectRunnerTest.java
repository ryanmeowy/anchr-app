package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.application.model.AgentMessage;
import com.anchr.core.conversation.application.model.AgentModelResponse;
import com.anchr.core.conversation.application.model.AgentTokenUsage;
import com.anchr.core.conversation.application.model.ConversationGenerationResult;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.domain.port.AgentModelPort;
import com.anchr.core.conversation.domain.port.ConversationGenerationPort;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentEffectRunnerTest {

    @Test
    void modelEffectParsesJsonFallbackIntoSemanticFinalAnswerEvent() {
        ObjectMapper mapper = new ObjectMapper();
        AgentModelPort model = request -> new AgentModelResponse(
                "{\"action\":\"final\",\"answerType\":\"CHAT\",\"answer\":\"ok\",\"citedSegmentIds\":[]}",
                List.of(), new AgentTokenUsage(2, 1), "model", "stop", "request");
        AgentEffectRunner runner = runner(model, mapper);
        AgentState state = state();

        AgentEvent event = runner.execute(state,
                new AgentCommand.CallModel(true, "JSON", 1, 1, 100, Duration.ofSeconds(1)),
                ConversationProgressListener.NOOP);

        assertThat(event).isInstanceOf(AgentEvent.ModelCompleted.class);
        AgentEvent.ModelCompleted completed = (AgentEvent.ModelCompleted) event;
        assertThat(completed.decision()).isEqualTo(new AgentModelDecision.FinalAnswer(
                new AgentFinalAnswer(AgentAnswerType.CHAT, "ok", List.of())));
        assertThat(completed.response().usage()).isEqualTo(new AgentTokenUsage(2, 1));
    }

    @Test
    void completionEffectReturnsValidatedNoEvidenceWithoutMutatingState() {
        ObjectMapper mapper = new ObjectMapper();
        AgentEffectRunner runner = runner(request -> { throw new AssertionError(); }, mapper);
        AgentState state = state();
        UnverifiedAgentAnswer submitted = new UnverifiedAgentAnswer(
                new AgentFinalAnswer(AgentAnswerType.NO_EVIDENCE, "raw", List.of()), null, null);

        AgentEvent verified = runner.execute(state, new AgentCommand.VerifyAnswer(submitted),
                ConversationProgressListener.NOOP);

        assertThat(verified).isInstanceOf(AgentEvent.AnswerAccepted.class);
        assertThat(((AgentEvent.AnswerAccepted) verified).answer())
                .isInstanceOf(VerifiedNoEvidenceAnswer.class);
        assertThat(state.phase()).isEqualTo(AgentWorkflowPhase.PLANNING);
        assertThat(state.stepCount()).isZero();
    }

    @Test
    void completionEffectConvertsVerifierExceptionIntoFailureEvent() {
        ObjectMapper mapper = new ObjectMapper();
        AgentAnswerVerifier verifier = mock(AgentAnswerVerifier.class);
        IllegalStateException failure = new IllegalStateException("verifier unavailable");
        when(verifier.verify(any(), any())).thenThrow(failure);
        AgentEffectRunner runner = new AgentEffectRunner(
                new AgentModelEffect(request -> { throw new AssertionError(); },
                        new AgentToolRegistry(List.of()), new AgentActionProtocol(mapper)),
                new AgentToolEffect(mock(AgentToolExecutor.class), mapper),
                new AgentCompletionEffect(mock(ConversationGenerationPort.class), verifier, mapper));
        UnverifiedAgentAnswer submitted = new UnverifiedAgentAnswer(
                new AgentFinalAnswer(AgentAnswerType.CHAT, "raw", List.of()), null, null);

        AgentEvent event = runner.execute(state(), new AgentCommand.VerifyAnswer(submitted),
                ConversationProgressListener.NOOP);

        assertThat(event).isEqualTo(new AgentEvent.AnswerVerificationFailed(
                failure, event.occurredAt()));
    }

    @Test
    void presentationDoesNotResetToInvalidCandidateWhenProviderSkippedDeltas() {
        ObjectMapper mapper = new ObjectMapper();
        ConversationGenerationPort generation = mock(ConversationGenerationPort.class);
        when(generation.generateStream(any(), any(), any())).thenReturn(
                new ConversationGenerationResult("伪造内容 {{segment:fake}}", 2, 1));
        AgentAnswerVerifier verifier = new AgentAnswerVerifier(
                new ConversationCitationMapper(), new AgentCitationPolicy());
        AgentEffectRunner runner = new AgentEffectRunner(
                new AgentModelEffect(request -> { throw new AssertionError(); },
                        new AgentToolRegistry(List.of()), new AgentActionProtocol(mapper)),
                new AgentToolEffect(mock(AgentToolExecutor.class), mapper),
                new AgentCompletionEffect(generation, verifier, mapper));
        List<String> resets = new ArrayList<>();
        ConversationProgressListener progress = new ConversationProgressListener() {
            @Override public void onAnswerReset(String answer) { resets.add(answer); }
        };

        AgentEvent event = runner.execute(state(), new AgentCommand.PresentAnswer(
                        new VerifiedPlainAnswer("已验证草稿"), true, 1, 1, 100,
                        Duration.ofSeconds(1)), progress);

        assertThat(event).isInstanceOf(AgentEvent.PresentationCompleted.class);
        assertThat(((AgentEvent.PresentationCompleted) event).answer().answer())
                .isEqualTo("已验证草稿");
        assertThat(resets).isEmpty();
    }

    @Test
    void finalizerNonJsonMarkerStillInfersKnowledgeAnswerType() {
        ObjectMapper mapper = new ObjectMapper();
        ConversationGenerationPort generation = mock(ConversationGenerationPort.class);
        when(generation.generateWithUsage(any(), any())).thenReturn(
                new ConversationGenerationResult("事实 {{segment:seg-1}}", 2, 1));
        AgentAnswerVerifier verifier = new AgentAnswerVerifier(
                new ConversationCitationMapper(), new AgentCitationPolicy());
        AgentEffectRunner runner = new AgentEffectRunner(
                new AgentModelEffect(request -> { throw new AssertionError(); },
                        new AgentToolRegistry(List.of()), new AgentActionProtocol(mapper)),
                new AgentToolEffect(mock(AgentToolExecutor.class), mapper),
                new AgentCompletionEffect(generation, verifier, mapper));
        AgentState state = state().registerEvidence(List.of(
                ConversationRetrievalCandidate.builder().segmentId("seg-1").content("事实").build()));

        AgentEvent event = runner.execute(state, new AgentCommand.CallEvidenceFinalizer(
                        1, 1, "test", null, 100, Duration.ofSeconds(1)),
                ConversationProgressListener.NOOP);

        assertThat(event).isInstanceOf(AgentEvent.FinalizerModelCompleted.class);
        AgentAnswerValidationOutcome validation =
                ((AgentEvent.FinalizerModelCompleted) event).validation();
        assertThat(validation).isEqualTo(new AgentAnswerValidationOutcome.Rejected(
                "INVALID_CITATION", "KNOWLEDGE 回答必须引用本轮证据", "invalid_agent_citation"));
    }

    private AgentEffectRunner runner(AgentModelPort model, ObjectMapper mapper) {
        AgentToolRegistry registry = new AgentToolRegistry(List.of());
        AgentAnswerVerifier verifier = new AgentAnswerVerifier(
                new ConversationCitationMapper(), new AgentCitationPolicy());
        return new AgentEffectRunner(
                new AgentModelEffect(model, registry, new AgentActionProtocol(mapper)),
                new AgentToolEffect(mock(AgentToolExecutor.class), mapper),
                new AgentCompletionEffect(mock(ConversationGenerationPort.class), verifier, mapper));
    }

    private AgentState state() {
        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
        request.setQuery("question");
        request.setAnswerMode("STRICT");
        AgentRunRequest run = new AgentRunRequest("run", "turn", "session", "user", request);
        AgentRuntimeSettings settings = new AgentRuntimeSettings(true,
                AgentRuntimeSettings.ToolCallMode.JSON, AgentRuntimeSettings.NativeToolChoice.REQUIRED,
                true, 4, 4, Duration.ofSeconds(10), Duration.ofSeconds(2),
                Duration.ofMinutes(10), Duration.ofSeconds(90), 2, Duration.ofMinutes(35),
                3, 500, 500_000, 12_000);
        return AgentState.initial(run, new AgentBudget(4, 4, 10_000), 0, settings,
                false, List.of(AgentMessage.system("system")));
    }
}
