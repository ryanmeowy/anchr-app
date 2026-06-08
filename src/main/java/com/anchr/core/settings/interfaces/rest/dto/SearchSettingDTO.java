package com.anchr.core.settings.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Search settings response.
 */
@Value
@Builder
public class SearchSettingDTO {
    int topK;
    int rerankWindow;
    int rrfK;
    double minScore;
    boolean hotUpdateSupported;
    List<String> requiresReindexFields;
    List<String> warnings;
}
