package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAnswerVerifierTest {
    private final AgentAnswerVerifier verifier = new AgentAnswerVerifier(
            new ConversationCitationMapper(), new AgentCitationPolicy());

    @Test
    void verifiesCurrentRunOwnershipMarkerBindingAndStableCitationIndexes() {
        AgentRunState state = state("STRICT");
        state.registerEvidence(List.of(
                evidence("seg-1", "asset-1"),
                evidence("seg-2", "asset-1"),
                evidence("seg-3", "asset-2")));

        AgentAnswerValidationOutcome outcome = verifier.verify(state, new AgentFinalAnswer(
                AgentAnswerType.KNOWLEDGE,
                "A {{segment:seg-2}}，B {{segment:seg-3}}，C {{segment:seg-1}}",
                List.of("seg-2", "seg-3", "seg-1")));

        assertThat(outcome).isInstanceOf(AgentAnswerValidationOutcome.Verified.class);
        VerifiedCitedAnswer answer = (VerifiedCitedAnswer)
                ((AgentAnswerValidationOutcome.Verified) outcome).answer();
        assertThat(answer.answer()).isEqualTo("A [1-1]，B [2-1]，C [1-2]");
        assertThat(answer.citations())
                .extracting(citation -> citation.getAssetCitationIndex()
                        + "-" + citation.getSegmentCitationIndex())
                .containsExactly("1-1", "2-1", "1-2");
    }

    @Test
    void rejectsForgedBlankAndMarkerMismatchedSegmentIds() {
        AgentRunState state = state("STRICT");
        state.registerEvidence(List.of(
                evidence("seg-1", "asset-1"),
                evidence("seg-2", "asset-1")));

        assertRejected(verifier.verify(state, knowledge(
                "伪造 {{segment:forged}}", List.of("forged"))), "INVALID_CITATION");
        assertRejected(verifier.verify(state, knowledge(
                "有效 {{segment:seg-1}}", List.of("seg-1", " "))), "INVALID_CITATION");
        assertRejected(verifier.verify(state, knowledge(
                "有效 {{segment:seg-1}}", List.of("seg-1", "seg-2"))),
                "CITATION_BINDING_MISMATCH");
        assertRejected(verifier.verify(state, knowledge(
                "只有声明，没有 Marker", List.of("seg-1"))),
                "MISSING_CITATION_MARKER");
        assertRejected(verifier.verify(state, knowledge(
                "模型自写伪引用 [9-9] {{segment:seg-1}}", List.of("seg-1"))),
                "UNTRUSTED_VISIBLE_CITATION");
    }

    @Test
    void verifiesNoEvidenceWithoutAllowingCitations() {
        AgentRunState state = state("SUMMARY");

        AgentAnswerValidationOutcome accepted = verifier.verify(state, new AgentFinalAnswer(
                AgentAnswerType.NO_EVIDENCE, "模型原始说明", List.of()));
        assertThat(((AgentAnswerValidationOutcome.Verified) accepted).answer())
                .isEqualTo(new VerifiedNoEvidenceAnswer(
                        "当前证据不足以形成可靠摘要。请补充相关资料或明确需要总结的文档范围。"));

        assertRejected(verifier.verify(state, new AgentFinalAnswer(
                AgentAnswerType.NO_EVIDENCE,
                "无证据 {{segment:seg-1}}", List.of("seg-1"))),
                "UNEXPECTED_NO_EVIDENCE_CITATION");
    }

    @Test
    void enforcesTenUniqueTwelveTotalAndThreePerParagraphLimits() {
        AgentRunState state = state("STRICT");
        List<ConversationRetrievalCandidate> evidence = IntStream.rangeClosed(1, 11)
                .mapToObj(index -> evidence("seg-" + index, "asset-" + index))
                .toList();
        state.registerEvidence(evidence);

        String tenUnique = IntStream.rangeClosed(1, 10)
                .mapToObj(index -> "段落 " + index + " {{segment:seg-" + index + "}}")
                .collect(java.util.stream.Collectors.joining("\n\n"));
        assertThat(verifier.verify(state, knowledge(tenUnique,
                IntStream.rangeClosed(1, 10).mapToObj(index -> "seg-" + index).toList())))
                .isInstanceOf(AgentAnswerValidationOutcome.Verified.class);

        String elevenUnique = tenUnique + "\n\n段落 11 {{segment:seg-11}}";
        assertRejected(verifier.verify(state, knowledge(elevenUnique,
                IntStream.rangeClosed(1, 11).mapToObj(index -> "seg-" + index).toList())),
                "CITATION_DENSITY_EXCEEDED");

        String twelveMarkers = IntStream.rangeClosed(1, 12)
                .mapToObj(index -> "段落 " + index + " {{segment:seg-1}}")
                .collect(java.util.stream.Collectors.joining("\n\n"));
        assertThat(verifier.verify(state, knowledge(twelveMarkers, List.of("seg-1"))))
                .isInstanceOf(AgentAnswerValidationOutcome.Verified.class);
        assertRejected(verifier.verify(state, knowledge(
                twelveMarkers + "\n\n段落 13 {{segment:seg-1}}", List.of("seg-1"))),
                "CITATION_DENSITY_EXCEEDED");

        assertThat(verifier.verify(state, knowledge(
                "一段 {{segment:seg-1}} {{segment:seg-2}} {{segment:seg-3}}",
                List.of("seg-1", "seg-2", "seg-3"))))
                .isInstanceOf(AgentAnswerValidationOutcome.Verified.class);
        assertRejected(verifier.verify(state, knowledge(
                "一段 {{segment:seg-1}} {{segment:seg-2}} {{segment:seg-3}} {{segment:seg-4}}",
                List.of("seg-1", "seg-2", "seg-3", "seg-4"))),
                "CITATION_DENSITY_EXCEEDED");
    }

    private AgentFinalAnswer knowledge(String answer, List<String> ids) {
        return new AgentFinalAnswer(AgentAnswerType.KNOWLEDGE, answer, ids);
    }

    private void assertRejected(AgentAnswerValidationOutcome outcome, String code) {
        assertThat(outcome).isInstanceOf(AgentAnswerValidationOutcome.Rejected.class);
        assertThat(((AgentAnswerValidationOutcome.Rejected) outcome).code()).isEqualTo(code);
    }

    private AgentRunState state(String answerMode) {
        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
        request.setQuery("问题");
        request.setAnswerMode(answerMode);
        AgentRunRequest run = new AgentRunRequest(
                "run-1", "turn-1", "session-1", "user-1", request);
        return new AgentRunState(run,
                new AgentBudget(20, 20, System.currentTimeMillis() + 60_000L),
                System.currentTimeMillis());
    }

    private ConversationRetrievalCandidate evidence(String segmentId, String assetId) {
        return ConversationRetrievalCandidate.builder()
                .segmentId(segmentId)
                .assetId(assetId)
                .kbId("kb-1")
                .title(assetId)
                .content("evidence " + segmentId)
                .build();
    }
}
