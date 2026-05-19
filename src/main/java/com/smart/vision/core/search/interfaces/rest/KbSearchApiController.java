package com.smart.vision.core.search.interfaces.rest;

import com.smart.vision.core.common.model.Result;
import com.smart.vision.core.search.application.UnifiedSearchService;
import com.smart.vision.core.search.interfaces.rest.dto.KbSearchQueryDTO;
import com.smart.vision.core.search.interfaces.rest.dto.KbSearchResultDTO;
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
public class KbSearchApiController {

    private final UnifiedSearchService unifiedSearchService;

    @PostMapping("/kb")
    public Result<List<KbSearchResultDTO>> searchKb(@Valid @RequestBody KbSearchQueryDTO query) {
        return Result.success(unifiedSearchService.search(query));
    }
}
