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
        assertThat(result.getAnswerText()).doesNotContain("建议改写检索问题");
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
        assertThat(result.getAnswerText()).doesNotContain("建议改写检索问题");
        verifyNoInteractions(conversationRewritePort);
    }

    @Test
    void generate_shouldUseOriginalContentInsteadOfSnippetAsModelEvidence() {
        String originalContent = "InnoDB 是 MySQL 的事务型存储引擎，支持事务、行级锁、外键以及崩溃恢复能力。".repeat(4);
        ConversationRetrievalCandidate candidate = ConversationRetrievalCandidate.builder()
                .segmentId("seg_original_content")
                .score(0.88D)
                .content(originalContent)
                .snippet("InnoDB 摘要")
                .build();
        ConversationCitation citation = buildCitation("seg_original_content", "InnoDB 摘要");
        when(conversationRewritePort.generateText(anyString()))
                .thenReturn("{\"status\":\"ANSWERED\",\"answer\":\"InnoDB 支持事务和崩溃恢复。[1]\"}");

        var result = service.generate(
                "InnoDB 有什么能力",
                "InnoDB 功能",
                AnswerMode.STRICT,
                List.of(candidate),
                List.of(citation)
        );

        var promptCaptor = forClass(String.class);
        verify(conversationRewritePort).generateText(promptCaptor.capture());
        assertThat(result.isFallbackUsed()).isFalse();
        assertThat(promptCaptor.getValue())
                .contains("content=" + originalContent)
                .doesNotContain("content=InnoDB 摘要");
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
        assertThat(result.getAnswerText()).doesNotContain("建议改写检索问题");
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
                .thenReturn("{\"status\":\"ANSWERED\",\"answer\":\"InnoDB 支持事务和行级锁。[2]\"}");

        var result = service.generate(
                "那 InnoDB 呢",
                "mysql 架构中的 InnoDB 作用",
                AnswerMode.STRICT,
                List.of(candidate),
                List.of(citation)
        );

        assertThat(result.isFallbackUsed()).isTrue();
        assertThat(result.getFallbackReason()).isEqualTo("invalid_answer_citation");
        assertThat(result.getAnswerText()).contains("- " + longEvidence + "[1]");
        assertThat(result.getAnswerInputSegmentIds()).containsExactly("seg_003");
    }

    @Test
    void generate_shouldTreatModelDeclaredNoEvidenceAsNoEvidenceFallback() {
        String longEvidence = "检索内容与问题主题接近，但没有提供开放式回答定义。".repeat(5);
        ConversationRetrievalCandidate candidate = buildCandidate("seg_no_evidence", longEvidence);
        ConversationCitation citation = buildCitation("seg_no_evidence", longEvidence);
        when(conversationRewritePort.generateText(anyString()))
                .thenReturn("{\"status\":\"NO_EVIDENCE\",\"answer\":\"模型可以使用任意拒答文案。\"}");

        var result = service.generate(
                "开放式回答的定义",
                "开放式回答 定义",
                AnswerMode.STRICT,
                List.of(candidate),
                List.of(citation)
        );

        assertThat(result.isFallbackUsed()).isTrue();
        assertThat(result.getFallbackReason()).isEqualTo("no_evidence_model_declared_no_evidence");
        assertThat(result.getAnswerInputSegmentIds()).isEmpty();
        assertThat(result.getAnswerText()).contains("未找到足够内容支持该问题");
    }

    @Test
    void generate_shouldFallbackWhenStructuredStatusIsMissing() {
        String longEvidence = "InnoDB 支持事务和行级锁，且具备崩溃恢复能力。".repeat(5);
        ConversationRetrievalCandidate candidate = buildCandidate("seg_missing_status", longEvidence);
        ConversationCitation citation = buildCitation("seg_missing_status", longEvidence);
        when(conversationRewritePort.generateText(anyString()))
                .thenReturn("{\"answer\":\"未找到足够内容支持该问题\"}");

        var result = service.generate(
                "那 InnoDB 呢",
                "mysql 架构中的 InnoDB 作用",
                AnswerMode.STRICT,
                List.of(candidate),
                List.of(citation)
        );

        assertThat(result.isFallbackUsed()).isTrue();
        assertThat(result.getFallbackReason()).isEqualTo("invalid_model_response");
        assertThat(result.getAnswerInputSegmentIds()).containsExactly("seg_missing_status");
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
                .thenReturn("{\"status\":\"ANSWERED\",\"answer\":\"简短总结。[1][2]\"}");

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
        assertThat(promptCaptor.getValue()).contains("引用编号必须紧跟在它所支持的总结、事实或结论之后");
        assertThat(promptCaptor.getValue()).contains("禁止输出“参考来源”“引用来源”“References”");
        assertThat(promptCaptor.getValue()).contains("[3]");
        assertThat(promptCaptor.getValue()).doesNotContain("[4]");
        assertThat(result.getAnswerInputSegmentIds()).containsExactly("seg_1", "seg_2");
    }

    @Test
    void generate_shouldFilterAndRenumberCitationsByFirstAppearance() {
        List<ConversationRetrievalCandidate> candidates = List.of(
                buildCandidate("seg_1", "证据一描述生成式回答的基本定义。".repeat(4)),
                buildCandidate("seg_2", "证据二描述抽取式回答的基本定义。".repeat(4)),
                buildCandidate("seg_3", "证据三比较生成式回答与抽取式回答。".repeat(4))
        );
        List<ConversationCitation> citations = candidates.stream()
                .map(candidate -> buildCitation(candidate.getSegmentId(), candidate.getSnippet()))
                .toList();
        when(conversationRewritePort.generateText(anyString()))
                .thenReturn("{\"status\":\"ANSWERED\",\"answer\":\"先引用第三条[3]，再引用第一条[1]，重复第三条[3]。\"}");

        var result = service.generate(
                "比较两种回答方式",
                "生成式回答和抽取式回答比较",
                AnswerMode.STRICT,
                candidates,
                citations
        );

        assertThat(result.isFallbackUsed()).isFalse();
        assertThat(result.getAnswerText()).isEqualTo("先引用第三条[1]，再引用第一条[2]，重复第三条。");
        assertThat(result.getAnswerInputSegmentIds()).containsExactly("seg_3", "seg_1");
    }

    @Test
    void generate_shouldUseOneReferenceNumberForMultipleSegmentsFromTheSameAsset() {
        ConversationRetrievalCandidate first = buildCandidate(
                "seg_1", "同一文档第一处证据介绍生成式回答。".repeat(4));
        first.setAssetId("asset-1");
        ConversationRetrievalCandidate second = buildCandidate(
                "seg_2", "同一文档第二处证据介绍抽取式回答。".repeat(4));
        second.setAssetId("asset-1");
        ConversationCitation firstCitation = buildCitation("seg_1", first.getSnippet());
        firstCitation.setAssetId("asset-1");
        ConversationCitation secondCitation = buildCitation("seg_2", second.getSnippet());
        secondCitation.setAssetId("asset-1");
        when(conversationRewritePort.generateText(anyString()))
                .thenReturn("{\"status\":\"ANSWERED\",\"answer\":\"两处证据共同支持结论[1][2]。\"}");

        var result = service.generate(
                "比较回答方式",
                "生成式与抽取式回答比较",
                AnswerMode.STRICT,
                List.of(first, second),
                List.of(firstCitation, secondCitation)
        );

        assertThat(result.isFallbackUsed()).isFalse();
        assertThat(result.getAnswerText()).isEqualTo("两处证据共同支持结论[1]。");
        assertThat(result.getAnswerInputSegmentIds()).containsExactly("seg_1", "seg_2");
    }

    @Test
    void generate_shouldFallbackWhenAnsweredResponseHasNoCitation() {
        String evidence = "InnoDB 支持事务、行级锁和崩溃恢复能力。".repeat(5);
        ConversationRetrievalCandidate candidate = buildCandidate("seg_missing_citation", evidence);
        ConversationCitation citation = buildCitation("seg_missing_citation", evidence);
        when(conversationRewritePort.generateText(anyString()))
                .thenReturn("{\"status\":\"ANSWERED\",\"answer\":\"InnoDB 是事务型存储引擎。\"}");

        var result = service.generate(
                "InnoDB 是什么",
                "InnoDB 定义",
                AnswerMode.STRICT,
                List.of(candidate),
                List.of(citation)
        );

        assertThat(result.isFallbackUsed()).isTrue();
        assertThat(result.getFallbackReason()).isEqualTo("missing_answer_citation");
        assertThat(result.getAnswerInputSegmentIds()).containsExactly("seg_missing_citation");
    }

    @Test
    void generate_shouldUseExplorePolicyAndPrompt() {
        String evidence = "InnoDB 事务日志可用于崩溃恢复，并通过 redo log 保障已提交事务的持久性。";
        ConversationRetrievalCandidate candidate = buildCandidate("seg_explore", evidence);
        candidate.setScore(0.09D);
        ConversationCitation citation = buildCitation("seg_explore", evidence);
        when(conversationRewritePort.generateText(anyString()))
                .thenReturn("{\"status\":\"ANSWERED\",\"answer\":\"证据显示 redo log 与崩溃恢复相关。[1]\\n可能方向：继续查看 checkpoint 机制。\"}");

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
