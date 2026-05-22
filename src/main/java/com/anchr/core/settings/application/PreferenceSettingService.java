package com.anchr.core.settings.application;

import com.anchr.core.settings.application.model.PreferenceSetting;
import com.anchr.core.settings.domain.model.PreferenceTheme;

/**
 * Application service for appearance preferences.
 */
public interface PreferenceSettingService {

    PreferenceSetting get();

    PreferenceSetting update(PreferenceTheme theme);
}
