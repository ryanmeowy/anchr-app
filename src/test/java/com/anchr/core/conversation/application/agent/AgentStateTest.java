package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AgentMessage;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentStateTest {

    @Test
    void snapshotsCopyCollectionsAndNeverMutateOlderState() {
        List<AgentMessage> source = new ArrayList<>(List.of(AgentMessage.system("system")));
        AgentState original = AgentState.initial(null, new AgentBudget(4, 4, 1_000),
                0, null, false, source);
        source.add(AgentMessage.user("outside"));

        AgentState next = original.appendMessage(AgentMessage.user("inside"))
                .registerEvidence(List.of(ConversationRetrievalCandidate.builder()
                        .segmentId("seg-1").content("evidence").build()));

        assertThat(original.messages()).hasSize(1);
        assertThat(original.evidence()).isEmpty();
        assertThat(next.messages()).extracting(AgentMessage::content)
                .containsExactly("system", "inside");
        assertThat(next.evidence()).containsOnlyKeys("seg-1");
        assertThatThrownBy(() -> next.messages().add(AgentMessage.user("mutate")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> next.evidence().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void visualProjectionNeverEntersCitableEvidence() {
        var visual = ConversationRetrievalCandidate.builder().segmentId("visual")
                .segmentType("IMAGE_VISUAL").build();
        var missingId = ConversationRetrievalCandidate.builder().segmentId("  ")
                .content("not addressable").build();
        var ocr = ConversationRetrievalCandidate.builder().segmentId("ocr")
                .segmentType("IMAGE_OCR_BLOCK").content("recognized").build();

        AgentState state = AgentState.initial(null, new AgentBudget(4, 4, 1_000),
                0, null, false, List.of()).registerEvidence(List.of(visual, missingId, ocr));

        assertThat(state.evidence()).containsOnlyKeys("ocr");
    }

    @Test
    void evidenceValuesAreDefensivelyCopiedAcrossSnapshotBoundary() {
        List<String> hitSources = new ArrayList<>(List.of("vector"));
        var candidate = ConversationRetrievalCandidate.builder()
                .segmentId("seg-1").content("original")
                .explain(ConversationRetrievalCandidate.Explain.builder()
                        .strategyEffective("hybrid").hitSources(hitSources).build())
                .build();
        AgentState state = AgentState.initial(null, new AgentBudget(4, 4, 1_000),
                0, null, false, List.of()).registerEvidence(List.of(candidate));

        candidate.setContent("mutated source");
        hitSources.add("keyword");
        ConversationRetrievalCandidate exposed = state.evidence().get("seg-1");
        exposed.setContent("mutated accessor result");

        assertThat(state.evidence().get("seg-1").getContent()).isEqualTo("original");
        assertThat(state.evidence().get("seg-1").getExplain().getHitSources())
                .containsExactly("vector");
    }
}
