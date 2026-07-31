package com.anchr.core.search.application.model;

import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.settings.domain.model.RuntimeConfigType;

import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.CANDIDATE_MULTIPLIER;
import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.DOCUMENT_IMAGE_SIMILARITY;
import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.DOCUMENT_IMAGE_TOP_K;
import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.FUSION_ALPHA;
import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.FUSION_BETA;
import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.MAX_CANDIDATES;
import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.MAX_DOC_CHARS;
import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.RANK_CONSTANT;
import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.TEXT_SIMILARITY;
import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.TEXT_TOP_K;
import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.WINDOW_ENABLED;
import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.WINDOW_FACTOR;
import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.WINDOW_MAX;
import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.WINDOW_MIN;
import static com.anchr.core.settings.domain.model.SearchRuntimeConfigKey.WINDOW_SIZE;

public record SearchRuntimeSettings(
        int rankConstant,
        int candidateMultiplier,
        int maxCandidates,
        int textTopK,
        int documentImageTopK,
        float textSimilarity,
        float documentImageSimilarity,
        int maxDocChars,
        boolean windowEnabled,
        int windowSize,
        int windowFactor,
        int windowMin,
        int windowMax,
        double fusionAlpha,
        double fusionBeta
) {
    public static SearchRuntimeSettings load(RuntimeConfigUnit unit) {
        return new SearchRuntimeSettings(
                unit.getInt(RuntimeConfigType.SEARCH, RANK_CONSTANT, 60),
                unit.getInt(RuntimeConfigType.SEARCH, CANDIDATE_MULTIPLIER, 4),
                unit.getInt(RuntimeConfigType.SEARCH, MAX_CANDIDATES, 200),
                unit.getInt(RuntimeConfigType.SEARCH, TEXT_TOP_K, 80),
                unit.getInt(RuntimeConfigType.SEARCH, DOCUMENT_IMAGE_TOP_K, 40),
                unit.getFloat(RuntimeConfigType.SEARCH, TEXT_SIMILARITY, 0.75F),
                unit.getFloat(
                        RuntimeConfigType.SEARCH, DOCUMENT_IMAGE_SIMILARITY, 0.70F),
                unit.getInt(RuntimeConfigType.SEARCH, MAX_DOC_CHARS, 1200),
                unit.getBoolean(RuntimeConfigType.SEARCH, WINDOW_ENABLED, true),
                unit.getInt(RuntimeConfigType.SEARCH, WINDOW_SIZE, 40),
                unit.getInt(RuntimeConfigType.SEARCH, WINDOW_FACTOR, 3),
                unit.getInt(RuntimeConfigType.SEARCH, WINDOW_MIN, 20),
                unit.getInt(RuntimeConfigType.SEARCH, WINDOW_MAX, 80),
                unit.getDouble(RuntimeConfigType.SEARCH, FUSION_ALPHA, 0.6D),
                unit.getDouble(RuntimeConfigType.SEARCH, FUSION_BETA, 0.4D));
    }

    public static SearchRuntimeSettings defaults() {
        return new SearchRuntimeSettings(
                60, 4, 200, 80, 40, 0.75F, 0.70F,
                1200, true, 40, 3, 20, 80, 0.6D, 0.4D);
    }
}
