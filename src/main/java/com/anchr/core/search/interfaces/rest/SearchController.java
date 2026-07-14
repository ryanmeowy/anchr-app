package com.anchr.core.search.interfaces.rest;

import com.anchr.core.common.infrastructure.RequireAuth;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified kb search api for text + image retrieval.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private static final int REWRITE_TIMEOUT_MS = 1500;

    private final UnifiedSearchService unifiedSearchService;
    private final SearchAnswerService kbSearchAnswerService;
    private final SearchQueryRewriteService searchQueryRewriteService;
    private final SearchFollowUpService searchFollowUpService;

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    @PostMapping("/kb")
    public Result<SearchPageDTO> searchKb(@Valid @RequestBody SearchQueryDTO query) {
        String userQuery = query.getQuery();
        SearchRewriteResult rewrite = searchQueryRewriteService.rewrite(userQuery);
        SearchPageDTO page = unifiedSearchService.searchPage(query, rewrite);
        page.setRewrittenQuery(rewrite.getRewrittenQuery());
        page.setRewrittenKeywords(rewrite.getKeywords());
        applyQueryIntent(page, rewrite);
        if (Boolean.TRUE.equals(query.getWithAnswer())) {
            page.setAnswer(kbSearchAnswerService.answer(query, page.getItems()));
        }
        page.setSuggestedQuestions(
                searchFollowUpService.generate(userQuery, page.getItems()));
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
