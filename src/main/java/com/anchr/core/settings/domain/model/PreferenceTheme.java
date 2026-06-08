package com.anchr.core.settings.domain.model;

import java.util.Locale;

/**
 * User appearance preference.
 */
public enum PreferenceTheme {
    LIGHT,
    DARK,
    SYSTEM;

    public static PreferenceTheme parse(String value) {
        if (value == null || value.isBlank()) {
            return SYSTEM;
        }
        return PreferenceTheme.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
