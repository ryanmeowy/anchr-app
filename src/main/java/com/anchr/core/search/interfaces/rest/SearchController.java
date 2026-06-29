package com.anchr.core.search.interfaces.rest;

import com.anchr.core.common.model.Result;
import com.anchr.core.search.application.SearchAnswerService;
import com.anchr.core.search.application.SearchFollowUpService;
import com.anchr.core.search.application.SearchQueryRewriteService;
import com.anchr.core.search.application.UnifiedSearchService;
import com.anchr.core.search.application.model.SearchRewriteResult;
import com.anchr.core.search.interfaces.rest.dto.RetrievalInsightDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchPageDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Unified kb search api for text + image retrieval.
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final UnifiedSearchService unifiedSearchService;
    private final SearchAnswerService kbSearchAnswerService;
    private final SearchQueryRewriteService searchQueryRewriteService;
    private final SearchFollowUpService searchFollowUpService;

    @PostMapping("/kb")
    public Result<SearchPageDTO> searchKb(@Valid @RequestBody SearchQueryDTO query) {
        SearchRewriteResult rewriteResult = searchQueryRewriteService.rewrite(query.getQuery());
        List<String> keywords = rewriteResult.getKeywords();
        SearchPageDTO page = unifiedSearchService.searchPage(query, keywords);
        page.setRewrittenKeywords(keywords);
        applyQueryIntent(page, rewriteResult);
        if (Boolean.TRUE.equals(query.getWithAnswer())) {
            page.setAnswer(kbSearchAnswerService.answer(query, page.getItems()));
        }
        page.setSuggestedQuestions(
                searchFollowUpService.generate(query.getQuery(), page.getItems()));
        return Result.success(page);
    }

    private void applyQueryIntent(SearchPageDTO page, SearchRewriteResult rewriteResult) {
        RetrievalInsightDTO insight = page.getInsight();
        if (insight == null) {
            return;
        }
        RetrievalInsightDTO.QueryIntentDTO queryIntent = RetrievalInsightDTO.QueryIntentDTO.builder()
                .intent(rewriteResult.getIntent())
                .category(rewriteResult.getIntentCategory())
                .fallback(rewriteResult.isFallbackUsed())
                .build();
        insight.setQueryIntent(queryIntent);
    }

}
