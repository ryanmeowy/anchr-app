package com.anchr.core.home.interfaces.rest;

import com.anchr.core.auth.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.home.application.HomeSummaryService;
import com.anchr.core.home.interfaces.rest.dto.HomeSummaryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ask First home APIs.
 */
@RestController
@RequestMapping("/api/v1/home")
@RequiredArgsConstructor
public class HomeSummaryApiController {

    private final HomeSummaryService homeSummaryService;

    @RequireAuth
    @GetMapping("/summary")
    public Result<HomeSummaryDTO> summary() {
        return Result.success(homeSummaryService.summary());
    }
}
