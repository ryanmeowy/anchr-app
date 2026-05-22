package com.anchr.core.search.interfaces.rest;

import com.anchr.core.common.model.Result;
import com.anchr.core.search.application.KbSearchAnswerService;
import com.anchr.core.search.application.UnifiedSearchService;
import com.anchr.core.search.interfaces.rest.dto.KbAnswerDTO;
import com.anchr.core.search.interfaces.rest.dto.KbSearchPageDTO;
import com.anchr.core.search.interfaces.rest.dto.KbSearchQueryDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified kb search api for text + image retrieval.
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class KbSearchApiController {

    private final UnifiedSearchService unifiedSearchService;
    private final KbSearchAnswerService kbSearchAnswerService;

    @PostMapping("/kb")
    public Result<KbSearchPageDTO> searchKb(@Valid @RequestBody KbSearchQueryDTO query) {
        KbSearchPageDTO page = unifiedSearchService.searchPage(query);
        if (Boolean.TRUE.equals(query.getWithAnswer())) {
            page.setAnswer(kbSearchAnswerService.answer(query));
        }
        return Result.success(page);
    }

    @PostMapping("/kb-answer")
    public Result<KbAnswerDTO> answerKb(@Valid @RequestBody KbSearchQueryDTO query) {
        return Result.success(kbSearchAnswerService.answer(query));
    }
}
