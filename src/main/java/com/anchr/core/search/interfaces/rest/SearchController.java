package com.anchr.core.search.interfaces.rest;

import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.search.application.acl.SearchActivityAcl;
import com.anchr.core.search.application.SearchAnswerService;
import com.anchr.core.search.application.SearchFollowUpService;
import com.anchr.core.search.application.SearchQueryRewriteService;
import com.anchr.core.search.application.api.RetrievalPageQueryApi;
import com.anchr.core.search.application.api.model.RetrievalPageQuery;
import com.anchr.core.search.application.api.model.RetrievalPageResult;
import com.anchr.core.search.application.api.model.SearchAnswerRequest;
import com.anchr.core.search.application.api.model.SearchAnswerResult;
import com.anchr.core.search.application.model.SearchRewriteResult;
import com.anchr.core.search.interfaces.rest.assembler.SearchRestAssembler;
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

    private final RetrievalPageQueryApi retrievalPageQueryApi;
    private final SearchAnswerService kbSearchAnswerService;
    private final SearchQueryRewriteService searchQueryRewriteService;
    private final SearchFollowUpService searchFollowUpService;
    private final SearchActivityAcl searchActivityAcl;
    private final SearchRestAssembler searchRestAssembler;

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    @PostMapping("/kb")
    public Result<SearchPageDTO> searchKb(@Valid @RequestBody SearchQueryDTO query) {
        String userQuery = query.getQuery();
        SearchRewriteResult rewrite = searchQueryRewriteService.rewrite(userQuery);
        RetrievalPageQuery retrievalQuery = searchRestAssembler.toPageQuery(query, rewrite);
        RetrievalPageResult retrievalResult = retrievalPageQueryApi.query(retrievalQuery);
        SearchAnswerResult answer = null;
        if (Boolean.TRUE.equals(query.getWithAnswer())) {
            answer = kbSearchAnswerService.answer(
                    new SearchAnswerRequest(retrievalQuery.query(), query.getAnswerMode()),
                    retrievalResult.items());
        }
        var suggestedQuestions = searchFollowUpService.generate(userQuery, retrievalResult.items());
        searchActivityAcl.recordSearchExecuted(
                query, Math.toIntExact(Math.min(retrievalResult.total(), Integer.MAX_VALUE)));
        SearchPageDTO page = searchRestAssembler.toPageDto(
                retrievalResult, rewrite, answer, suggestedQuestions);
        return Result.success(page);
    }
}
