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
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

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
    private final Executor searchRewriteExecutor;

    @PostMapping("/kb")
    public Result<SearchPageDTO> searchKb(@Valid @RequestBody SearchQueryDTO query) {
        String userQuery = query.getQuery();

        CompletableFuture<SearchRewriteResult> rewriteFuture = CompletableFuture
                .supplyAsync(() -> searchQueryRewriteService.rewrite(userQuery),
                        searchRewriteExecutor);

        SearchPageDTO page = unifiedSearchService.searchPage(query, List.of());

        SearchRewriteResult rewriteResult = awaitRewrite(rewriteFuture, userQuery);

        if (!rewriteResult.isFallbackUsed() && !rewriteResult.getKeywords().isEmpty()) {
            page = unifiedSearchService.searchPage(query, rewriteResult.getKeywords());
            page.setRewrittenKeywords(rewriteResult.getKeywords());
        }

        applyQueryIntent(page, rewriteResult);
        if (Boolean.TRUE.equals(query.getWithAnswer())) {
            page.setAnswer(kbSearchAnswerService.answer(query, page.getItems()));
        }
        page.setSuggestedQuestions(
                searchFollowUpService.generate(userQuery, page.getItems()));
        return Result.success(page);
    }

    private SearchRewriteResult awaitRewrite(CompletableFuture<SearchRewriteResult> future, String query) {
        try {
            return future
                    .completeOnTimeout(buildTimeoutFallback(query), REWRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .get();
        } catch (Exception e) {
            log.warn("Rewrite future failed, query={}", query, e);
            return buildTimeoutFallback(query);
        }
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

    private SearchRewriteResult buildTimeoutFallback(String query) {
        SearchRewriteResult fallback = new SearchRewriteResult();
        fallback.setOriginalQuery(query);
        fallback.setKeywords(List.of());
        fallback.setFallbackUsed(true);
        return fallback;
    }
}
