package com.anchr.core.settings.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.config.AppSearchProperties;
import com.anchr.core.settings.application.SearchSettingService;
import com.anchr.core.settings.application.model.SearchSetting;
import com.anchr.core.settings.domain.repository.AppSettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Search settings service backed by app_setting and in-process hot update.
 */
@Service
@RequiredArgsConstructor
public class SearchSettingServiceImpl implements SearchSettingService {

    public static final String SETTING_KEY = "search";

    private final AppSettingRepository appSettingRepository;
    private final AppSearchProperties appSearchProperties;
    private final ObjectMapper objectMapper;

    @Override
    public SearchSetting get() {
        RequestUserContext context = UserContextHolder.get();
        appSettingRepository.find(context.workspaceId(), SETTING_KEY)
                .ifPresent(setting -> applyJson(setting.getSettingValue()));
        return current();
    }

    @Override
    public SearchSetting update(Integer topK, Integer rerankWindow, Integer rrfK, Double minScore) {
        if (topK == null && rerankWindow == null && rrfK == null && minScore == null) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "At least one search setting field is required.");
        }
        SearchSetting before = current();
        SearchSetting next = SearchSetting.builder()
                .topK(topK == null ? before.getTopK() : topK)
                .rerankWindow(rerankWindow == null ? before.getRerankWindow() : rerankWindow)
                .rrfK(rrfK == null ? before.getRrfK() : rrfK)
                .minScore(minScore == null ? before.getMinScore() : minScore)
                .hotUpdateSupported(true)
                .build();
        apply(next);
        RequestUserContext context = UserContextHolder.get();
        appSettingRepository.upsert(context.workspaceId(), SETTING_KEY, toJson(next), context.userId());
        return next;
    }

    private SearchSetting current() {
        return SearchSetting.builder()
                .topK(appSearchProperties.getPage().getDefaultPageSize())
                .rerankWindow(appSearchProperties.getRerank().getWindowSize())
                .rrfK(appSearchProperties.getRrf().getRankConstant())
                .minScore(appSearchProperties.getQualityAbsoluteMinScore())
                .hotUpdateSupported(true)
                .build();
    }

    private void applyJson(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            apply(SearchSetting.builder()
                    .topK(node.path("topK").asInt(appSearchProperties.getPage().getDefaultPageSize()))
                    .rerankWindow(node.path("rerankWindow").asInt(appSearchProperties.getRerank().getWindowSize()))
                    .rrfK(node.path("rrfK").asInt(appSearchProperties.getRrf().getRankConstant()))
                    .minScore(node.path("minScore").asDouble(appSearchProperties.getQualityAbsoluteMinScore()))
                    .hotUpdateSupported(true)
                    .build());
        } catch (Exception e) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Failed to parse search setting.", e);
        }
    }

    private void apply(SearchSetting setting) {
        appSearchProperties.getPage().setDefaultPageSize(setting.getTopK());
        appSearchProperties.getRerank().setWindowSize(setting.getRerankWindow());
        appSearchProperties.getRrf().setRankConstant(setting.getRrfK());
        appSearchProperties.setQualityAbsoluteMinScore(setting.getMinScore());
    }

    private String toJson(SearchSetting setting) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "topK", setting.getTopK(),
                    "rerankWindow", setting.getRerankWindow(),
                    "rrfK", setting.getRrfK(),
                    "minScore", setting.getMinScore()
            ));
        } catch (Exception e) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Failed to serialize search setting.", e);
        }
    }
}
