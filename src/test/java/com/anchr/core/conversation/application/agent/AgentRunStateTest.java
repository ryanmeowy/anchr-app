package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.search.domain.model.SegmentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunStateTest {

    @Test
    void visualProjectionShouldNeverEnterTheCitableEvidenceRegistry() {
        AgentRunState state = new AgentRunState(null, null, 0L);
        ConversationRetrievalCandidate visual =
                ConversationRetrievalCandidate.builder()
                        .segmentId("visual-1")
                        .segmentType(SegmentType.IMAGE_VISUAL.name())
                        .build();
        ConversationRetrievalCandidate ocr =
                ConversationRetrievalCandidate.builder()
                        .segmentId("ocr-1")
                        .segmentType(SegmentType.IMAGE_OCR_BLOCK.name())
                        .content("recognized text")
                        .build();

        state.registerEvidence(List.of(visual, ocr));

        assertThat(state.getEvidence()).containsOnlyKeys("ocr-1");
    }
}
