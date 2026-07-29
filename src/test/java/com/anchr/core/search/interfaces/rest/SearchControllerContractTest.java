package com.anchr.core.search.interfaces.rest;

import com.anchr.core.kb.application.ActivityEventService;
import com.anchr.core.search.application.SearchAnswerService;
import com.anchr.core.search.application.SearchFollowUpService;
import com.anchr.core.search.application.SearchQueryRewriteService;
import com.anchr.core.search.application.api.RetrievalPageQueryApi;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalInsight;
import com.anchr.core.search.application.api.model.RetrievalPageQuery;
import com.anchr.core.search.application.api.model.RetrievalPageResult;
import com.anchr.core.search.application.model.SearchRewriteResult;
import com.anchr.core.search.interfaces.rest.assembler.SearchRestAssembler;
import com.anchr.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchControllerContractTest {

    @Test
    void keepsExistingEndpointAndJsonWhileRecordingOnlyPublicSearch() throws Exception {
        AtomicReference<RetrievalPageQuery> capturedQuery = new AtomicReference<>();
        RetrievalPageQueryApi retrievalApi = query -> {
            capturedQuery.set(query);
            RetrievalHit hit = new RetrievalHit(
                    "TEXT_CHUNK", "title", "content", "TEXT", "PDF", "snippet", 1, 0.9D,
                    null, null, null, null, 1, List.of(), "seg-1", "kb-1", "asset-1",
                    "docs/a.pdf", null, null);
            return new RetrievalPageResult(
                    List.of(hit), 1, Map.of(), new RetrievalInsight(null, null, null, null, 9L));
        };
        SearchQueryRewriteService rewriteService = query -> {
            SearchRewriteResult result = new SearchRewriteResult();
            result.setOriginalQuery(query);
            result.setRewrittenQuery("rewritten query");
            result.setKeywords(List.of("rewritten", "query"));
            result.setIntent("LOOKUP");
            result.setIntentCategory("FACT");
            return result;
        };
        AtomicInteger activityCount = new AtomicInteger();
        AtomicReference<SearchQueryDTO> activityQuery = new AtomicReference<>();
        ActivityEventService activity = new NoOpActivityEventService() {
            @Override
            public void recordSearchExecuted(SearchQueryDTO query, int total) {
                activityCount.incrementAndGet();
                activityQuery.set(query);
                assertThat(total).isEqualTo(1);
            }
        };
        SearchAnswerService answerService = (request, hits) -> null;
        SearchFollowUpService followUpService = (query, hits) -> List.of("next?");
        SearchController controller = new SearchController(
                retrievalApi, answerService, rewriteService, followUpService,
                activity, new SearchRestAssembler());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/v1/search/kb")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"original query","limit":5,"kbIds":["kb-1"],"withAnswer":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[0].segmentId").value("seg-1"))
                .andExpect(jsonPath("$.data.items[0].assetId").value("asset-1"))
                .andExpect(jsonPath("$.data.rewrittenQuery").value("rewritten query"))
                .andExpect(jsonPath("$.data.rewrittenKeywords[0]").value("rewritten"))
                .andExpect(jsonPath("$.data.insight.queryIntent.intent").value("LOOKUP"))
                .andExpect(jsonPath("$.data.suggestedQuestions[0]").value("next?"));

        assertThat(capturedQuery.get().query()).isEqualTo("rewritten query");
        assertThat(capturedQuery.get().kbIds()).containsExactly("kb-1");
        assertThat(activityCount).hasValue(1);
        assertThat(activityQuery.get().getQuery()).isEqualTo("original query");
    }

    private static class NoOpActivityEventService implements ActivityEventService {
        public void recordQuestionAsked(String sessionId, String turnId, String question, List<String> kbScope) {}
        public void recordCitationOpened(CitationContext cxt) {}
        public void recordDocumentImported(String taskId, String kbId, String status,
                                           int totalCount, int successCount, int failureCount, int runningCount) {}
        public void recordSearchExecuted(SearchQueryDTO query, int total) {}
        public void deleteBySessionId(String sessionId) {}
    }
}
