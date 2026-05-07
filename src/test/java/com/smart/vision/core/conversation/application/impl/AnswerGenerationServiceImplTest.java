package com.smart.vision.core.conversation.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.vision.core.conversation.application.model.ConversationRetrievalCandidate;
import com.smart.vision.core.conversation.domain.model.ConversationCitation;
import com.smart.vision.core.conversation.domain.port.ConversationRewritePort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnswerGenerationServiceImplTest {

    @Mock
    private ConversationRewritePort conversationRewritePort;

    private AnswerGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AnswerGenerationServiceImpl(
                conversationRewritePort,
                new ObjectMapper(),
                new SimpleMeterRegistry()
        );
    }

    @Test
    void generate_shouldFallbackWhenNoGroundingSegment() {
        var result = service.generate(
                "那 InnoDB 呢",
                "mysql 架构中的 InnoDB 作用",
                List.of(),
                List.of()
        );

        assertThat(result.isFallbackUsed()).isTrue();
        assertThat(result.getFallbackReason()).isEqualTo("no_evidence_no_grounding_segment");
        assertThat(result.getAnswerText()).contains("建议改写检索问题：mysql 架构中的 InnoDB 作用");
        assertThat(result.getAnswerText()).contains("你可以重试：");
        verifyNoInteractions(conversationRewritePort);
    }

    @Test
    void generate_shouldFallbackWhenEvidenceTooShort() {
        ConversationRetrievalCandidate candidate = ConversationRetrievalCandidate.builder()
                .segmentId("seg_001")
                .score(0.88D)
                .snippet("too short")
                .build();
        ConversationCitation citation = new ConversationCitation();
        citation.setSegmentId("seg_001");
        citation.setSnippet("短证据");

        var result = service.generate(
                "那 InnoDB 呢",
                "mysql 架构中的 InnoDB 作用",
                List.of(candidate),
                List.of(citation)
        );

        assertThat(result.isFallbackUsed()).isTrue();
        assertThat(result.getFallbackReason()).isEqualTo("no_evidence_evidence_too_short");
        assertThat(result.getAnswerText()).contains("建议改写检索问题：mysql 架构中的 InnoDB 作用");
        verifyNoInteractions(conversationRewritePort);
    }

    @Test
    void generate_shouldFallbackWhenRetrievalScoreTooLow() {
        String longEvidence = "InnoDB 支持事务和行级锁，且具备崩溃恢复能力。".repeat(4);
        ConversationRetrievalCandidate candidate = ConversationRetrievalCandidate.builder()
                .segmentId("seg_002")
                .score(0.03D)
                .snippet(longEvidence)
                .build();
        ConversationCitation citation = new ConversationCitation();
        citation.setSegmentId("seg_002");
        citation.setSnippet(longEvidence);

        var result = service.generate(
                "那 InnoDB 呢",
                "mysql 架构中的 InnoDB 作用",
                List.of(candidate),
                List.of(citation)
        );

        assertThat(result.isFallbackUsed()).isTrue();
        assertThat(result.getFallbackReason()).isEqualTo("no_evidence_low_retrieval_score");
        assertThat(result.getAnswerText()).contains("建议改写检索问题：mysql 架构中的 InnoDB 作用");
        verifyNoInteractions(conversationRewritePort);
    }

    @Test
    void generate_shouldFallbackWhenModelReturnsInvalidCitation() {
        String longEvidence = "InnoDB 支持事务和行级锁，且具备崩溃恢复能力。".repeat(5);
        ConversationRetrievalCandidate candidate = ConversationRetrievalCandidate.builder()
                .segmentId("seg_003")
                .score(0.88D)
                .snippet(longEvidence)
                .build();
        ConversationCitation citation = new ConversationCitation();
        citation.setSegmentId("seg_003");
        citation.setSnippet(longEvidence);
        when(conversationRewritePort.generateText(anyString()))
                .thenReturn("{\"answer\":\"InnoDB 支持事务和行级锁。[2]\"}");

        var result = service.generate(
                "那 InnoDB 呢",
                "mysql 架构中的 InnoDB 作用",
                List.of(candidate),
                List.of(citation)
        );

        assertThat(result.isFallbackUsed()).isTrue();
        assertThat(result.getFallbackReason()).isEqualTo("invalid_answer_citation");
        assertThat(result.getAnswerText()).contains("- [1] " + longEvidence);
        assertThat(result.getAnswerInputSegmentIds()).containsExactly("seg_003");
    }

    @Test
    void generate_shouldFallbackWhenModelFails() {
        String longEvidence = "InnoDB 支持事务和行级锁，且具备崩溃恢复能力。".repeat(5);
        ConversationRetrievalCandidate candidate = ConversationRetrievalCandidate.builder()
                .segmentId("seg_004")
                .score(0.88D)
                .snippet(longEvidence)
                .build();
        ConversationCitation citation = new ConversationCitation();
        citation.setSegmentId("seg_004");
        citation.setSnippet(longEvidence);
        when(conversationRewritePort.generateText(anyString()))
                .thenThrow(new RuntimeException("timeout"));

        var result = service.generate(
                "那 InnoDB 呢",
                "mysql 架构中的 InnoDB 作用",
                List.of(candidate),
                List.of(citation)
        );

        assertThat(result.isFallbackUsed()).isTrue();
        assertThat(result.getFallbackReason()).isEqualTo("model_unavailable");
        assertThat(result.getAnswerText()).contains("根据当前知识库，先给出可确认的信息：");
        assertThat(result.getAnswerInputSegmentIds()).containsExactly("seg_004");
    }
}
