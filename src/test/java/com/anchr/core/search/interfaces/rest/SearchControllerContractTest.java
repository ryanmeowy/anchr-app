package com.anchr.core.search.interfaces.rest;

import com.anchr.core.search.application.acl.SearchActivityAcl;
import com.anchr.core.search.application.SearchAnswerService;
import com.anchr.core.search.application.SearchFollowUpService;
import com.anchr.core.search.application.SearchQueryRewriteService;
import com.anchr.core.search.application.api.RetrievalTopNQueryApi;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalInsight;
import com.anchr.core.search.application.api.model.RetrievalTopNQuery;
import com.anchr.core.search.application.api.model.RetrievalTopNResult;
import com.anchr.core.search.application.model.SearchRewriteResult;
import com.anchr.core.search.interfaces.rest.assembler.SearchRestAssembler;
import com.anchr.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchControllerContractTest {

    @Test
    void exposesTopNContractAndRecordsReturnedCount() throws Exception {
        AtomicReference<RetrievalTopNQuery> capturedQuery = new AtomicReference<>();
        RetrievalTopNQueryApi retrievalApi = query -> {
            capturedQuery.set(query);
            RetrievalHit hit = new RetrievalHit(
                    "TEXT_CHUNK", "title", "content", "TEXT", "PDF", "snippet", 1, 0.9D,
                    null, null, null, null, 1, List.of(), "seg-1", "kb-1", "asset-1",
                    "docs/a.pdf", null, null);
            return new RetrievalTopNResult(
                    List.of(hit),
                    Map.of("assetTypes", List.of()),
                    new RetrievalInsight(null, null, null, null, 9L));
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
        SearchActivityAcl activity = mock(SearchActivityAcl.class);
        SearchAnswerService answerService = (request, hits) -> null;
        SearchFollowUpService followUpService = (query, hits) -> List.of("next?");
        SearchController controller = new SearchController(
                retrievalApi, answerService, rewriteService, followUpService,
                activity, new SearchRestAssembler());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/v1/search/kb")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"original query","limit":5,"kbIds":["kb-1"],
                                 "withAnswer":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[0].segmentId").value("seg-1"))
                .andExpect(jsonPath("$.data.items[0].assetId").value("asset-1"))
                .andExpect(jsonPath("$.data.returnedCount").value(1))
                .andExpect(jsonPath("$.data.windowFacets.assetTypes").isArray())
                .andExpect(jsonPath("$.data.total").doesNotExist())
                .andExpect(jsonPath("$.data.facets").doesNotExist())
                .andExpect(jsonPath("$.data.rewrittenQuery").value("rewritten query"))
                .andExpect(jsonPath("$.data.rewrittenKeywords[0]").value("rewritten"))
                .andExpect(jsonPath("$.data.insight.queryIntent.intent").value("LOOKUP"))
                .andExpect(jsonPath("$.data.suggestedQuestions[0]").value("next?"));

        assertThat(capturedQuery.get().query()).isEqualTo("rewritten query");
        assertThat(capturedQuery.get().kbIds()).containsExactly("kb-1");
        ArgumentCaptor<SearchQueryDTO> activityQuery = ArgumentCaptor.forClass(SearchQueryDTO.class);
        verify(activity).recordSearchExecuted(activityQuery.capture(), org.mockito.ArgumentMatchers.eq(1));
        assertThat(activityQuery.getValue().getQuery()).isEqualTo("original query");
    }
}
