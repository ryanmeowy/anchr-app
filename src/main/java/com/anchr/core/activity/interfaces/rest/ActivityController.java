package com.anchr.core.activity.interfaces.rest;

import com.anchr.core.activity.application.api.ActivityQueryApi;
import com.anchr.core.activity.interfaces.rest.assembler.ActivityRestAssembler;
import com.anchr.core.activity.interfaces.rest.dto.RecentCitationListDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentDocumentListDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentQuestionListDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentSearchListDTO;
import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Activity query APIs. */
@RestController
@RequestMapping("/api/v1/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityQueryApi activityQueryApi;
    private final ActivityRestAssembler assembler;

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    @GetMapping("/recent-questions")
    public Result<RecentQuestionListDTO> recentQuestions(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return Result.success(assembler.toQuestions(activityQueryApi.recentQuestions(limit, cursor)));
    }

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    @GetMapping("/recent-citations")
    public Result<RecentCitationListDTO> recentCitations(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return Result.success(assembler.toCitations(activityQueryApi.recentCitations(limit, cursor)));
    }

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    @GetMapping("/recent-search")
    public Result<RecentSearchListDTO> recentSearch(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return Result.success(assembler.toSearches(activityQueryApi.recentSearch(limit, cursor)));
    }

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    @GetMapping("/recent-document")
    public Result<RecentDocumentListDTO> recentDocument(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return Result.success(assembler.toDocuments(activityQueryApi.recentDocument(limit, cursor)));
    }
}
