package com.anchr.core.kb.interfaces.rest;

import com.anchr.core.kb.application.ActivityQueryService;
import com.anchr.core.kb.interfaces.rest.dto.RecentCitationListDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentDocumentListDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentQuestionListDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentSearchListDTO;
import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Activity query APIs.
 */
@RestController
@RequestMapping("/api/v1/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityQueryService activityQueryService;

    @RequireAuth
    @GetMapping("/recent-questions")
    public Result<RecentQuestionListDTO> recentQuestions(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return Result.success(activityQueryService.recentQuestions(limit, cursor));
    }

    @RequireAuth
    @GetMapping("/recent-citations")
    public Result<RecentCitationListDTO> recentCitations(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return Result.success(activityQueryService.recentCitations(limit, cursor));
    }

    @RequireAuth
    @GetMapping("/recent-search")
    public Result<RecentSearchListDTO> recentSearch(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return Result.success(activityQueryService.recentSearch(limit, cursor));
    }

    @RequireAuth
    @GetMapping("/recent-document")
    public Result<RecentDocumentListDTO> recentDocument(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return Result.success(activityQueryService.recentDocument(limit, cursor));
    }
}
