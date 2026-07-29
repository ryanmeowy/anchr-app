package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.acl.ConversationRetrievalAcl;
import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.application.assembler.ConversationResultCardMapper;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.application.model.AnswerGenerationResult;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.application.model.ConversationRetrievalResult;
import com.anchr.core.conversation.application.model.RewriteResult;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.search.domain.model.SegmentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationMessagePipelineVisualEvidenceTest {

    @Test
    void resultCardShouldKeepVisualHitWhileAnswerUsesOnlyOcr() {
        ConversationRetrievalCandidate visual = candidate(
                "visual-1", SegmentType.IMAGE_VISUAL, "", 1.0D);
        ConversationRetrievalCandidate ocr = candidate(
                "ocr-1", SegmentType.IMAGE_OCR_BLOCK,
                "recognized text", 0.9D);
        ConversationRetrievalResult retrieval = new ConversationRetrievalResult();
        retrieval.setTopCandidates(List.of(visual, ocr));
        AtomicReference<List<ConversationRetrievalCandidate>> answerInput =
                new AtomicReference<>();
        ConversationRetrievalAcl retrievalAcl = mock(ConversationRetrievalAcl.class);
        when(retrievalAcl.generateCitationReasons(any())).thenReturn(java.util.Map.of());
        ConversationMessagePipeline pipeline = new ConversationMessagePipeline(
                null,
                (query, limit, kbIds, modalities, assetIds) -> retrieval,
                new ConversationCitationMapper(),
                new ConversationResultCardMapper(),
                (userQuery, rewrittenQuery, answerMode, candidates, citations) -> {
                    answerInput.set(candidates);
                    return new AnswerGenerationResult();
                },
                new ConversationTurnCodec(new ObjectMapper()),
                retrievalAcl);
        ConversationMessageRequestDTO request =
                new ConversationMessageRequestDTO();
        request.setQuery("question");
        RewriteResult rewrite = new RewriteResult();
        rewrite.setRewrittenQuery("question");

        var result = pipeline.execute(request, rewrite);

        assertThat(result.resultCards()).singleElement().satisfies(card -> {
            assertThat(card.getPrimaryHit().getSegmentId())
                    .isEqualTo("visual-1");
            assertThat(card.getAdditionalHits()).singleElement()
                    .satisfies(hit -> assertThat(hit.getSegmentId())
                            .isEqualTo("ocr-1"));
        });
        assertThat(answerInput.get())
                .extracting(ConversationRetrievalCandidate::getSegmentId)
                .containsExactly("ocr-1");
    }

    private ConversationRetrievalCandidate candidate(
            String id,
            SegmentType type,
            String content,
            double score
    ) {
        return ConversationRetrievalCandidate.builder()
                .segmentId(id)
                .kbId("kb-1")
                .assetId("asset-1")
                .assetType("IMAGE")
                .segmentType(type.name())
                .title("diagram.png")
                .sourceRef("images/diagram.png")
                .content(content)
                .snippet(content)
                .score(score)
                .build();
    }
}
