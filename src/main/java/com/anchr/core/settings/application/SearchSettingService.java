package com.anchr.core.settings.application;

import com.anchr.core.settings.application.model.SearchSetting;

/**
 * Application service for hot-updatable search settings.
 */
public interface SearchSettingService {

    SearchSetting get();

    SearchSetting update(Integer topK, Integer rerankWindow, Integer rrfK, Double minScore);
}
