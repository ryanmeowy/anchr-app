package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.*;
import com.anchr.core.conversation.application.assembler.*;
import com.anchr.core.conversation.application.model.*;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TraditionalRagPipelineTest {
    @Test
    void searchesOnceWithCompactQueryAndGeneratesOnceUsingFullQuestionWithinOriginalScope() {
        var rewriteService = mock(TraditionalRagRewriteService.class);
        var retrieval = mock(ConversationRetrievalOrchestrator.class);
        var answers = mock(AnswerGenerationService.class);
        var pipeline = new ConversationMessagePipeline(rewriteService, retrieval,
                new ConversationCitationMapper(), new ConversationResultCardMapper(), answers);
        var request = new ConversationMessageRequestDTO();
        request.setQuery("那它的限制和生效时机呢？"); request.setKbIds(List.of("kb1"));
        request.setAssetIdList(List.of("a1")); request.setPreferredModalities(List.of("TEXT")); request.setLimit(5);
        var rewrite = new RewriteResult(); rewrite.setResolvedQuestion("组件A的数量限制和配置生效时机？");
        rewrite.setRewrittenQuery(rewrite.getResolvedQuestion());
        rewrite.setKeywords(List.of("组件A", "数量限制", "配置 生效时机"));
        when(rewriteService.rewrite(anyString(), anyString())).thenReturn(rewrite);
        var result = new ConversationRetrievalResult();
        result.setTopCandidates(List.of(ConversationRetrievalCandidate.builder().segmentId("s1").assetId("a1")
                .kbId("kb1").segmentType("TEXT_CHUNK").content("证据内容").score(0.9).build()));
        when(retrieval.retrieve(anyString(), anyList(), any(), anyList(), anyList(), anyList())).thenReturn(result);
        when(answers.generate(anyString(), anyString(), any(), anyList(), anyList())).thenReturn(new AnswerGenerationResult());
        pipeline.execute("session", request);
        verify(rewriteService).rewrite("session", request.getQuery());
        verify(retrieval).retrieve(rewrite.getRewrittenQuery(), rewrite.getKeywords(), 5, List.of("kb1"), List.of("TEXT"), List.of("a1"));
        verify(answers).generate(eq(request.getQuery()), eq(rewrite.getResolvedQuestion()), eq(AnswerMode.STRICT), anyList(), anyList());
        verifyNoMoreInteractions(rewriteService, retrieval, answers);
        String trace = new ConversationRetrievalTraceBuilder(new com.fasterxml.jackson.databind.ObjectMapper())
                .buildTraceJson(request, rewrite, result, new AnswerGenerationResult());
        assertThat(trace).contains("resolvedQuestion", "searchQuery", "keywords").doesNotContain("traditionalRag", "coverage");
    }

    @Test
    void emptyResultsDoNotTriggerAnotherSearch() {
        var retrieval = mock(ConversationRetrievalOrchestrator.class);
        var answers = mock(AnswerGenerationService.class);
        var pipeline = new ConversationMessagePipeline(null, retrieval, new ConversationCitationMapper(), new ConversationResultCardMapper(), answers);
        var request = new ConversationMessageRequestDTO(); request.setQuery("无资料的问题");
        var rewrite = new RewriteResult(); rewrite.setRewrittenQuery("不存在的实体");
        when(retrieval.retrieve(anyString(), any(), any(), any(), any())).thenReturn(new ConversationRetrievalResult());
        when(answers.generate(anyString(), anyString(), any(), anyList(), anyList())).thenReturn(new AnswerGenerationResult());
        pipeline.execute(request, rewrite);
        verify(retrieval).retrieve(eq("不存在的实体"), any(), any(), any(), any());
        verifyNoMoreInteractions(retrieval);
    }
}
