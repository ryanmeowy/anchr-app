package com.anchr.core.settings.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.settings.application.PreferenceSettingService;
import com.anchr.core.settings.application.model.PreferenceSetting;
import com.anchr.core.settings.domain.model.PreferenceTheme;
import com.anchr.core.settings.domain.repository.AppSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Appearance preference persisted in app_setting.
 */
@Service
@RequiredArgsConstructor
public class PreferenceSettingServiceImpl implements PreferenceSettingService {

    public static final String SETTING_KEY = "preferences";

    private final AppSettingRepository appSettingRepository;
    private final ObjectMapper objectMapper;

    @Override
    public PreferenceSetting get() {
        RequestUserContext context = UserContextHolder.get();
        PreferenceTheme theme = appSettingRepository.find(SETTING_KEY)
                .map(setting -> readTheme(setting.getSettingValue()))
                .orElse(PreferenceTheme.SYSTEM);
        return PreferenceSetting.builder().theme(theme).build();
    }

    @Override
    public PreferenceSetting update(PreferenceTheme theme) {
        PreferenceTheme safeTheme = theme == null ? PreferenceTheme.SYSTEM : theme;
        RequestUserContext context = UserContextHolder.get();
        appSettingRepository.upsert(SETTING_KEY, toJson(safeTheme), context.userId());
        return PreferenceSetting.builder().theme(safeTheme).build();
    }

    private PreferenceTheme readTheme(String json) {
        try {
            return PreferenceTheme.parse(objectMapper.readTree(json).path("theme").asText());
        } catch (Exception e) {
            return PreferenceTheme.SYSTEM;
        }
    }

    private String toJson(PreferenceTheme theme) {
        try {
            return objectMapper.writeValueAsString(Map.of("theme", theme.name()));
        } catch (Exception e) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Failed to serialize preference setting.", e);
        }
    }
}
