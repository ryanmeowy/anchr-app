package com.anchr.core.settings.application.model;

import com.anchr.core.settings.domain.model.PreferenceTheme;
import lombok.Builder;
import lombok.Value;

/**
 * Appearance preference setting.
 */
@Value
@Builder
public class PreferenceSetting {
    PreferenceTheme theme;
}
