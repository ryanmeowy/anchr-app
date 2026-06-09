package com.anchr.core.search.interfaces.rest;

import com.anchr.core.auth.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Web search provider entry. Disabled until a provider is configured.
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class WebSearchApiController {

    @org.springframework.beans.factory.annotation.Value("${app.web-search.enabled:false}")
    private boolean enabled;

    @RequireAuth
    @PostMapping("/web")
    public Result<WebSearchResultDTO> searchWeb(@Valid @RequestBody WebSearchRequestDTO request) {
        if (!enabled) {
            return Result.success(WebSearchResultDTO.builder()
                    .enabled(false)
                    .provider("")
                    .answer("")
                    .sources(List.of())
                    .reason("Web search provider not configured.")
                    .build());
        }
        return Result.success(WebSearchResultDTO.builder()
                .enabled(true)
                .provider("manual")
                .answer("Web search provider is enabled, but no external adapter is configured.")
                .sources(List.of())
                .reason("External web search adapter is not configured.")
                .build());
    }

    @Data
    public static class WebSearchRequestDTO {
        @NotBlank
        private String query;
    }

    @Value
    @Builder
    public static class WebSearchResultDTO {
        boolean enabled;
        String provider;
        String answer;
        List<WebSearchSourceDTO> sources;
        String reason;
    }

    @Value
    @Builder
    public static class WebSearchSourceDTO {
        String title;
        String url;
        String snippet;
        String sourceType;
    }
}
