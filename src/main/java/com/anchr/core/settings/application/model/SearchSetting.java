package com.anchr.core.settings.application.model;

import lombok.Builder;
import lombok.Value;

/**
 * Runtime search settings exposed to the settings page.
 */
@Value
@Builder
public class SearchSetting {
    int topK;
    int rerankWindow;
    int rrfK;
    double minScore;
    boolean hotUpdateSupported;
}
