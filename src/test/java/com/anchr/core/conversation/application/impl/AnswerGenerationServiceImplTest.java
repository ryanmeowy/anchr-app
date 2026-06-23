package com.anchr.core.conversation.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.conversation.application.model.AnswerMode;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.domain.model.ConversationCitation;
import com.anchr.core.conversation.domain.port.ConversationRewritePort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
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
                AnswerMode.STRICT,
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
                AnswerMode.STRICT,
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
                AnswerMode.STRICT,
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
                AnswerMode.STRICT,
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
                AnswerMode.STRICT,
                List.of(candidate),
                List.of(citation)
        );

        assertThat(result.isFallbackUsed()).isTrue();
        assertThat(result.getFallbackReason()).isEqualTo("model_unavailable");
        assertThat(result.getAnswerText()).contains("根据当前知识库，先给出可确认的信息：");
        assertThat(result.getAnswerInputSegmentIds()).containsExactly("seg_004");
    }

    @Test
    void generate_shouldUseSummaryPromptAndLimitGroundingSegments() {
        List<ConversationRetrievalCandidate> candidates = List.of(
                buildCandidate("seg_1", "证据一说明 MySQL SQL 层负责解析、优化和执行查询。".repeat(3)),
                buildCandidate("seg_2", "证据二说明存储引擎层负责数据读写和事务能力。".repeat(3)),
                buildCandidate("seg_3", "证据三说明 InnoDB 支持事务、行级锁和崩溃恢复。".repeat(3)),
                buildCandidate("seg_4", "证据四说明查询缓存已经在新版本中被移除。".repeat(3))
        );
        List<ConversationCitation> citations = candidates.stream()
                .map(candidate -> buildCitation(candidate.getSegmentId(), candidate.getSnippet()))
                .toList();
        when(conversationRewritePort.generateText(anyString()))
                .thenReturn("{\"answer\":\"简短总结。[1][2]\"}");

        var result = service.generate(
                "总结 MySQL 架构",
                "总结 MySQL 架构",
                AnswerMode.SUMMARY,
                candidates,
                citations
        );

        var promptCaptor = forClass(String.class);
        verify(conversationRewritePort).generateText(promptCaptor.capture());
        assertThat(result.isFallbackUsed()).isFalse();
        assertThat(promptCaptor.getValue()).contains("回答模式：SUMMARY");
        assertThat(promptCaptor.getValue()).contains("最多3条要点");
        assertThat(promptCaptor.getValue()).contains("[3]");
        assertThat(promptCaptor.getValue()).doesNotContain("[4]");
        assertThat(result.getAnswerInputSegmentIds()).containsExactly("seg_1", "seg_2", "seg_3");
    }

    @Test
    void generate_shouldUseExplorePolicyAndPrompt() {
        String evidence = "InnoDB 事务日志可用于崩溃恢复，并通过 redo log 保障已提交事务的持久性。";
        ConversationRetrievalCandidate candidate = buildCandidate("seg_explore", evidence);
        candidate.setScore(0.09D);
        ConversationCitation citation = buildCitation("seg_explore", evidence);
        when(conversationRewritePort.generateText(anyString()))
                .thenReturn("{\"answer\":\"证据显示 redo log 与崩溃恢复相关。[1]\\n可能方向：继续查看 checkpoint 机制。\"}");

        var result = service.generate(
                "redo log 还能怎么分析",
                "redo log 崩溃恢复 checkpoint",
                AnswerMode.EXPLORE,
                List.of(candidate),
                List.of(citation)
        );

        var promptCaptor = forClass(String.class);
        verify(conversationRewritePort).generateText(promptCaptor.capture());
        assertThat(result.isFallbackUsed()).isFalse();
        assertThat(promptCaptor.getValue()).contains("回答模式：EXPLORE");
        assertThat(promptCaptor.getValue()).contains("可能方向/建议");
        assertThat(promptCaptor.getValue()).contains("推测必须明确标注");
    }

    private ConversationRetrievalCandidate buildCandidate(String segmentId, String snippet) {
        return ConversationRetrievalCandidate.builder()
                .segmentId(segmentId)
                .score(0.88D)
                .snippet(snippet)
                .build();
    }

    private ConversationCitation buildCitation(String segmentId, String snippet) {
        ConversationCitation citation = new ConversationCitation();
        citation.setSegmentId(segmentId);
        citation.setSnippet(snippet);
        return citation;
    }
}
